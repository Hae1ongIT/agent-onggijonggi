-- 04·DATA
--
-- 이름은 scripts/glossary/data-glossary.md 축약 사전을 따른다.
-- 근거: 04·DATA 협업 채팅 데이터 모델 설계 — Tenant 격리 경계와 그 안의 Workspace 트리.
--
-- tnn = tenant(고객사·회사 격리 경계), wrk = workspace, prn = parent.
--
-- Tenant는 부서가 아니다 — 부서·업무그룹·세부그룹은 전부 wrk_node의 노드다. Tenant를 부서로 두면
-- 향후 RLS와 복합 FK의 격리 경계가 조직 개편 때마다 흔들린다.
-- 트리 깊이를 고정하지 않는 이유도 같다: 한 단계 추가·삭제가 스키마 변경이 되면 안 된다.
--
-- 기존 데이터 전제: 두 테이블 모두 신규다. path·kind처럼 default 없는 not null 컬럼이 있어 빈
-- 테이블에서만 성립하며, 그래서 backfill 단계가 없다.
-- 기존 doc.dept를 이 트리로 옮기는 이관은 별도 마이그레이션이며 여기서 하지 않는다. 그 단계는
-- dept → Workspace 매핑에 누락·중복이 1건이라도 있으면 복사를 시작하지 않는다.
--
-- Workspace 계층에서 파생되는 Casbin g2 규칙을 재생성하는 트리거는 casbin_rule 테이블이 아직
-- 없어 이번에 걸지 않는다. 그 테이블을 만드는 마이그레이션에서 함께 건다.

-- tnn: 고객사·회사 격리 경계. 하위 행이 있으면 물리 삭제를 막는다(FK RESTRICT).
create table tnn (
    id          uuid         primary key,
    -- 사람이 SQL·설정에서 쓰는 불변 자연키. UUID만으로는 운영 중에 어느 Tenant인지 못 읽는다.
    tnn_key     varchar(64)  not null unique,
    name        varchar(255) not null,
    status      varchar(16)  not null default 'ACTIVE' check (status in ('ACTIVE', 'INACTIVE')),
    inactive_at timestamptz,
    created_at  timestamptz  not null default now(),
    updated_at  timestamptz  not null default now(),
    -- status와 시각이 어긋나면 "언제 비활성됐나"를 신뢰할 수 없다.
    constraint tnn_inactive_at_matches_status check ((status = 'INACTIVE') = (inactive_at is not null))
);

-- wrk_node: Tenant 아래 가변 깊이 Workspace 트리(부서→업무그룹→세부그룹).
--           정본은 prn_id 하나이고 path는 root→self 캐시다. path는 아래 트리거가 채운다.
create table wrk_node (
    id          uuid         not null,
    tnn_id      uuid         not null references tnn (id),
    prn_id      uuid,
    kind        varchar(16)  not null check (kind in ('COMMON', 'ORG', 'WORK')),
    name        varchar(255) not null check (length(btrim(name)) > 0),
    path        uuid[]       not null,
    status      varchar(16)  not null default 'ACTIVE' check (status in ('ACTIVE', 'INACTIVE')),
    inactive_at timestamptz,
    created_at  timestamptz  not null default now(),
    updated_at  timestamptz  not null default now(),
    primary key (id),
    -- Tenant 내부 참조는 전부 이 후보키를 거친다. 단일 UUID FK면 다른 Tenant의 노드에 붙는 것을 DB가 못 막는다.
    constraint wrk_node_tnn_id_id_key unique (tnn_id, id),
    -- prn_id가 null인 루트는 MATCH SIMPLE 기본 동작이라 이 FK 검사를 건너뛴다.
    constraint wrk_node_prn_fkey foreign key (tnn_id, prn_id) references wrk_node (tnn_id, id),
    constraint wrk_node_inactive_at_matches_status check ((status = 'INACTIVE') = (inactive_at is not null)),
    -- COMMON은 Tenant 전사 공통 루트다. 하위에 두면 "전사 공통"이 성립하지 않는다.
    constraint wrk_node_common_is_root check (kind <> 'COMMON' or prn_id is null)
);

-- 활성 형제끼리 같은 이름 금지. PostgreSQL 16의 NULLS NOT DISTINCT로 루트(prn_id is null)도 같은
-- 규칙에 묶는다 — 없으면 null끼리 서로 달라서 루트 이름 중복이 그냥 통과한다.
create unique index ux_wrk_node_sibling_name
    on wrk_node (tnn_id, prn_id, lower(name)) nulls not distinct
    where status = 'ACTIVE';

-- Tenant당 활성 COMMON 루트는 하나뿐이다.
create unique index ux_wrk_node_common_root
    on wrk_node (tnn_id)
    where status = 'ACTIVE' and kind = 'COMMON';

-- 기본 조회는 "이 Tenant, 이 부모 밑 활성 자식"이다. 부분 유니크 인덱스를 걸었으므로 목록 조회
-- 술어도 status = 'ACTIVE'로 같아야 한다.
-- GIN path 인덱스는 실제 쿼리 계획에서 필요하다고 확인되기 전에는 만들지 않는다.
create index idx_wrk_node_tnn_prn_status on wrk_node (tnn_id, prn_id, status);

-- path는 DB가 계산한다. 애플리케이션이 넣은 값은 무시하고 덮어쓴다 — 정본은 prn_id 하나뿐이고,
-- 캐시를 손으로 채우게 두면 둘이 어긋난 순간 조상 조회가 조용히 틀린 답을 낸다.
create or replace function wrk_node_path_sync() returns trigger
    language plpgsql as $$
declare
    parent_path uuid[];
    parent_tnn  uuid;
begin
    if new.prn_id is null then
        new.path := array [new.id];
        return new;
    end if;

    select w.path, w.tnn_id into parent_path, parent_tnn
    from wrk_node w
    where w.id = new.prn_id
    for share;

    if parent_path is null then
        raise exception '부모 Workspace가 없습니다: prn_id=%', new.prn_id
            using errcode = 'foreign_key_violation';
    end if;

    -- 복합 FK가 이미 막지만, 여기서 먼저 확인해야 다른 Tenant의 path가 조립되지 않는다.
    if parent_tnn <> new.tnn_id then
        raise exception '부모와 Tenant가 다릅니다: 부모=%, 자신=%', parent_tnn, new.tnn_id
            using errcode = 'foreign_key_violation';
    end if;

    -- 자기 자신·조상을 부모로 지정하면 트리가 순환한다.
    if new.id = any (parent_path) then
        raise exception '순환 참조입니다: id=%가 이미 부모 경로에 있습니다', new.id
            using errcode = 'check_violation';
    end if;

    new.path := parent_path || new.id;
    return new;
end;
$$;

create trigger trg_wrk_node_path_sync
    before insert or update on wrk_node
    for each row execute function wrk_node_path_sync();

-- reparent(부모 변경)는 하위 노드가 전혀 없는 leaf만 허용한다. 이유가 둘이다.
--   1. 자식이 있으면 자식들의 path 캐시가 통째로 낡는다. 재귀 갱신은 큰 서브트리에서 조용히 오래 잠근다.
--   2. 파생 Casbin g2 재생성을 같은 트랜잭션에서 보장해야 하는데 casbin_rule이 아직 없다.
-- 이관이 필요하면 새 노드를 만들고 명시적 절차로 옮긴다.
--
-- 비활성 자식이 있어도 거부한다 — 비활성 자식도 path를 갖고 있어서, 부모가 옮겨지면 그 캐시가
-- 낡은 채 남고 나중에 재활성화되는 순간 틀린 조상 경로가 살아난다.
create or replace function wrk_node_reparent_guard() returns trigger
    language plpgsql as $$
begin
    -- Tenant 이동은 어떤 경우에도 허용하지 않는다 — 격리 경계 자체가 바뀐다.
    if new.tnn_id <> old.tnn_id then
        raise exception 'Workspace의 Tenant는 바꿀 수 없습니다: id=%', old.id
            using errcode = 'check_violation';
    end if;

    if new.prn_id is distinct from old.prn_id
        and exists (select 1 from wrk_node c where c.tnn_id = old.tnn_id and c.prn_id = old.id)
    then
        raise exception '하위 노드가 있는 Workspace는 부모를 바꿀 수 없습니다: id=%', old.id
            using errcode = 'check_violation';
    end if;

    return new;
end;
$$;

-- 두 BEFORE 트리거는 이름 알파벳 순으로 돌지만 순서에 의존하지 않는다 —
-- path_sync는 new.path만 쓰고, reparent_guard는 prn_id·tnn_id만 읽는다.
create trigger trg_wrk_node_reparent_guard
    before update on wrk_node
    for each row execute function wrk_node_reparent_guard();
