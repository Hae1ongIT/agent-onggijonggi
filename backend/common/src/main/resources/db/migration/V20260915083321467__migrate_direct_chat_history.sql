-- 04·DATA
-- 변경 이유:
-- 1:1 대화의 정본을 chat_sess/chat_msg에서 공용 thr(DIRECT)/thr_mbr/msg로 옮긴다. id를
-- 보존해 클라이언트 sessionId와 과거 링크를 바꾸지 않고, 이후 저장·이력 경로가 한 모델을 쓰게 한다.
--
-- 기존 데이터 전제:
-- chat_msg의 한 sess_id 안에서 created_at은 동률이 없어야 한다. seq는 그 시간순으로만
-- 결정할 수 있고, UUID를 임의 tiebreak로 쓰면 과거 대화의 실제 순서를 꾸며 내게 된다.
--
-- 이관·중단 조건:
-- 대상 thr/msg/thr_mbr ID 충돌이나 created_at 동률이 있으면 아무 행도 옮기지 않고 migration을
-- 실패시킨다. 이 파일은 하나의 Flyway 트랜잭션이며 chat_sess/chat_msg는 #164까지 삭제하지 않는다.
do $$
begin
    if exists (select 1 from chat_sess s join thr t on t.id = s.id)
        or exists (select 1 from chat_msg c join msg m on m.id = c.id)
        or exists (select 1 from chat_sess s join thr_mbr p on p.id = s.id) then
        raise exception 'DIRECT chat migration ID collision';
    end if;

    if exists (
        select 1
        from chat_msg
        group by sess_id, created_at
        having count(*) > 1
    ) then
        raise exception 'DIRECT chat migration requires unique created_at within each session';
    end if;
end;
$$;

insert into thr (
    id, kind, status, drc_own_user_id, created_user_id, title, next_seq,
    created_at, updated_at, locked_at, archived_at
)
select s.id, 'DIRECT', 'ACTIVE', s.user_id, s.user_id, s.title, count(c.id),
       s.created_at, s.updated_at, null, null
from chat_sess s
left join chat_msg c on c.sess_id = s.id
group by s.id, s.user_id, s.title, s.created_at, s.updated_at;

-- DIRECT는 생성자 한 명만 ACTIVE OWNER로 둔다. sess_id를 참가자 ID로 재사용해 별도 UUID 생성
-- 함수·확장 의존 없이도 이관이 결정적이며, 위 preflight가 이 ID 충돌을 함께 막는다.
insert into thr_mbr (id, thr_id, user_id, role, status, created_by_user_id, created_at)
select s.id, s.id, s.user_id, 'OWNER', 'ACTIVE', s.user_id, s.created_at
from chat_sess s;

insert into msg (
    id, thr_id, seq, rpl_msg_id, ath_kind, thr_mbr_id, status, content,
    pyl_json, src_json, created_at, completed_at
)
select c.id,
       c.sess_id,
       row_number() over (partition by c.sess_id order by c.created_at) - 1,
       null,
       case c.role
           when 'user' then 'HUMAN'
           when 'assistant' then 'AGENT'
           when 'system' then 'SYSTEM'
       end,
       case when c.role = 'user' then c.sess_id else null end,
       'COMPLETE',
       c.content,
       null,
       c.src_json,
       c.created_at,
       c.created_at
from chat_msg c;
