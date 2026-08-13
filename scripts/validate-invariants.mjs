#!/usr/bin/env node
// 마이그레이션 SQL이 설계 불변식을 깨지 않는지 정적으로 검사한다.
//
// 설계 불변식 5종:
//   1. 조각은 필수 메타데이터 7종을 모두 가진다
//   2. 조각 acc_tag는 상위 doc의 사본과 일치한다
//   3. adt_log에는 어떤 FK도 없다
//   4. 같은 doc_key 재색인 후 구버전 조각은 없다
//   5. 색인·검색 임베딩 모델은 동일하다
//
// 지금 강제하는 것은 불변식 3(감사 로그에 FK 없음)뿐이다.
// 나머지 1·2·4·5는 ES 조각에 대한 조건이라 색인 코드와 ES가 생긴 뒤에야 검증 대상이 존재한다.
//
// 사용:
//   node scripts/validate-invariants.mjs <파일...>
//   node scripts/validate-invariants.mjs --staged
//
// exit 0 = 통과, exit 1 = 위반. 의존성 없음(node 내장만).

import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');

/**
 * FK를 가져서는 안 되는 테이블.
 * `adt_log`는 사용자·세션이 삭제돼도 감사 기록이 남아야 하므로 요청자를 값 복사(`req_id`)로 둔다.
 * FK를 걸면 cascade 시 감사 기록이 사라지거나, restrict로 사용자 삭제 자체가 막힌다. 둘 다 감사를 무력화한다.
 */
const FK_FORBIDDEN = new Map([
	['adt_log', '감사 로그는 사용자·세션 삭제와 무관하게 남아야 한다(불변식 3). 요청자는 값 복사(req_id)로 둔다.'],
]);

const stripComment = (line) => line.replace(/--.*$/, '');
const netParens = (line) => (line.match(/\(/g) ?? []).length - (line.match(/\)/g) ?? []).length;
const hasFk = (s) => /\breferences\b|\bforeign\s+key\b/i.test(s);

/** SQL에서 금지 테이블에 FK가 걸리는 지점을 찾는다. */
export function findViolations(text) {
	const found = [];
	const lines = text.split(/\r?\n/);

	let depth = 0;
	let bodyTable = null;   // create table 본문을 읽는 중인 테이블
	let alterTable = null;  // 여러 줄에 걸친 alter table 대상

	for (let i = 0; i < lines.length; i++) {
		const line = stripComment(lines[i]);
		const t = line.trim();
		if (!t) continue;

		if (bodyTable) {
			if (FK_FORBIDDEN.has(bodyTable) && hasFk(t)) {
				found.push({ line: i + 1, table: bodyTable, snippet: t });
			}
			depth += netParens(line);
			if (depth <= 0) bodyTable = null;
			continue;
		}

		if (alterTable) {
			if (FK_FORBIDDEN.has(alterTable) && hasFk(t)) {
				found.push({ line: i + 1, table: alterTable, snippet: t });
			}
			if (t.includes(';')) alterTable = null;
			continue;
		}

		const created = /^create\s+table\s+(?:if\s+not\s+exists\s+)?([a-z_]\w*)/i.exec(t);
		if (created) {
			const name = created[1].toLowerCase();
			if (FK_FORBIDDEN.has(name) && hasFk(t)) {
				found.push({ line: i + 1, table: name, snippet: t });
			}
			depth = netParens(line);
			if (depth > 0) bodyTable = name;
			continue;
		}

		const altered = /^alter\s+table\s+(?:only\s+)?([a-z_]\w*)/i.exec(t);
		if (altered) {
			const name = altered[1].toLowerCase();
			if (FK_FORBIDDEN.has(name) && hasFk(t)) {
				found.push({ line: i + 1, table: name, snippet: t });
			}
			if (!t.includes(';')) alterTable = name;
		}
	}
	return found;
}

/** 마이그레이션 SQL만 대상으로 한다. */
export function inScope(path) {
	return /(^|\/)(db\/)?migration\/[^/]+\.sql$/.test(path.replace(/\\/g, '/'));
}

export function checkFile(path, text = null) {
	const content = text ?? readFileSync(path, 'utf8');
	return findViolations(content).map((v) => ({ path, ...v }));
}

function stagedTargets() {
	const out = execFileSync('git', ['diff', '--cached', '--name-only', '--diff-filter=ACM'], {
		cwd: ROOT,
		encoding: 'utf8',
	});
	return out
		.split(/\r?\n/)
		.filter(Boolean)
		.filter(inScope)
		.map((f) => ({ path: join(ROOT, f), text: execFileSync('git', ['show', `:${f}`], { cwd: ROOT, encoding: 'utf8' }) }));
}

function main() {
	const args = process.argv.slice(2);
	if (args.length === 0) {
		process.stderr.write('사용: node scripts/validate-invariants.mjs <파일...> | --staged\n');
		process.exit(2);
	}

	const targets = args[0] === '--staged'
		? stagedTargets()
		: args.filter((p) => existsSync(p)).map((path) => ({ path, text: null }));

	const violations = targets.flatMap(({ path, text }) => checkFile(path, text));

	if (violations.length === 0) {
		process.stdout.write(`[불변식] 통과 — 검사 ${targets.length}개 파일\n`);
		return;
	}

	process.stderr.write('[불변식] 위반 — 설계 계약을 깨는 변경입니다:\n');
	for (const v of violations) {
		const rel = v.path.replace(ROOT, '').replace(/^[\\/]/, '').replace(/\\/g, '/');
		process.stderr.write(`  ${rel}:${v.line}  '${v.table}'에 FK를 걸 수 없습니다\n`);
		process.stderr.write(`    ${v.snippet}\n`);
		process.stderr.write(`    ${FK_FORBIDDEN.get(v.table)}\n`);
	}
	process.exit(1);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
	main();
}
