#!/usr/bin/env node
// 마이그레이션 SQL·ES 매핑의 식별자가 축약 사전(scripts/glossary/data-glossary.md)에 등재된 토큰으로만
// 이뤄졌는지 검사한다.
//
// 검사 대상은 "정의 위치"의 이름뿐이다 — 테이블명, 컬럼명, ES 필드명.
// 인덱스·제약 이름은 별도 관례를 따르므로 이번 범위가 아니다.
//
// 사용:
//   node scripts/validate-glossary.mjs <파일...>   지정한 파일 검사
//   node scripts/validate-glossary.mjs --staged    스테이징된 대상 파일 검사
//
// exit 0 = 통과, exit 1 = 위반. 의존성 없음(node 내장만).

import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const GLOSSARY_PATH = join(ROOT, 'scripts', 'glossary', 'data-glossary.md');

/** create table 본문에서 컬럼이 아닌 테이블 제약 정의를 여는 키워드. */
const NON_COLUMN_STARTERS = new Set([
	'constraint', 'primary', 'foreign', 'unique', 'check', 'exclude', 'like', 'inherits',
]);

/** 축약 사전의 첫 컬럼(축약어)만 모은다. */
export function loadGlossary(path = GLOSSARY_PATH) {
	const dict = new Set();
	for (const line of readFileSync(path, 'utf8').split(/\r?\n/)) {
		if (!line.trim().startsWith('|')) continue;
		const first = line.split('|')[1]?.trim() ?? '';
		const m = /^`([a-z0-9_]+)`$/.exec(first);
		if (m) dict.add(m[1]);
	}
	return dict;
}

const stripComment = (line) => line.replace(/--.*$/, '');
const netParens = (line) => (line.match(/\(/g) ?? []).length - (line.match(/\)/g) ?? []).length;

/** SQL에서 테이블명·컬럼명을 뽑는다. */
export function extractSqlIdentifiers(text) {
	const found = [];
	const lines = text.split(/\r?\n/);
	let depth = 0;
	let inBody = false;

	for (let i = 0; i < lines.length; i++) {
		const line = stripComment(lines[i]);
		const t = line.trim();
		if (!t) continue;

		if (!inBody) {
			const table = /^create\s+table\s+(?:if\s+not\s+exists\s+)?([a-z_]\w*)/i.exec(t);
			if (table) {
				found.push({ name: table[1], line: i + 1, kind: '테이블' });
				depth = netParens(line);
				inBody = depth > 0;
				continue;
			}
			// `alter table x` 와 `add column y` 가 다른 줄에 오는 경우가 흔하므로 add 절만으로도 잡는다.
			const added = /^(?:alter\s+table\s+[\w.]+\s+)?add\s+column\s+(?:if\s+not\s+exists\s+)?([a-z_]\w*)/i.exec(t);
			if (added) found.push({ name: added[1], line: i + 1, kind: '컬럼' });
			continue;
		}

		// create table 본문의 최상위 깊이에서만 컬럼 정의를 읽는다.
		if (depth === 1) {
			const col = /^([a-z_]\w*)\s+\S/i.exec(t);
			if (col && !NON_COLUMN_STARTERS.has(col[1].toLowerCase())) {
				found.push({ name: col[1], line: i + 1, kind: '컬럼' });
			}
		}
		depth += netParens(line);
		if (depth <= 0) inBody = false;
	}
	return found;
}

/** ES 매핑 JSON에서 mappings.properties의 필드명을 뽑는다. */
export function extractEsFields(text) {
	const props = JSON.parse(text)?.mappings?.properties;
	if (!props) return [];

	const lines = text.split(/\r?\n/);
	return Object.keys(props).map((name) => ({
		name,
		// 라인 번호는 보고용이라 첫 등장 위치면 충분하다.
		line: lines.findIndex((l) => l.includes(`"${name}"`)) + 1,
		kind: 'ES 필드',
	}));
}

/** snake_case인지, `_` 토큰이 모두 사전에 있는지 검사한다. 조합 자체는 보지 않는다. */
export function checkIdentifier(name, dict) {
	if (!/^[a-z][a-z0-9_]*$/.test(name)) return 'snake_case 위반(소문자·숫자·밑줄만)';

	const unknown = name.split('_').filter((token) => !dict.has(token));
	return unknown.length > 0 ? `미등재 토큰: ${unknown.map((u) => `'${u}'`).join(', ')}` : null;
}

/** 파일 하나를 검사해 위반 목록을 돌려준다. */
export function checkFile(path, dict, text = null) {
	const content = text ?? readFileSync(path, 'utf8');

	let identifiers;
	try {
		identifiers = path.endsWith('.json') ? extractEsFields(content) : extractSqlIdentifiers(content);
	} catch {
		return []; // 파싱 불가(작성 중인 JSON 등)는 막지 않는다.
	}

	const violations = [];
	for (const id of identifiers) {
		const reason = checkIdentifier(id.name, dict);
		if (reason) violations.push({ path, ...id, reason });
	}
	return violations;
}

/** 검사 대상 경로인지 판정한다. --staged 로 넘어온 전체 변경분을 걸러내는 데 쓴다. */
export function inScope(path) {
	const p = path.replace(/\\/g, '/');
	return /(^|\/)(db\/)?migration\/[^/]+\.sql$/.test(p) || /es-[\w-]*index[\w-]*\.json$/.test(p);
}

function stagedFiles() {
	const out = execFileSync('git', ['diff', '--cached', '--name-only', '--diff-filter=ACM'], {
		cwd: ROOT,
		encoding: 'utf8',
	});
	return out.split(/\r?\n/).filter(Boolean).filter(inScope);
}

/**
 * 스테이징된 대상. 워킹트리가 아니라 **실제 커밋될 내용**(인덱스)을 읽는다 —
 * 좋은 버전을 stage하고 워킹트리만 고쳐둔 경우 판정이 달라지면 안 되기 때문이다.
 */
function stagedTargets() {
	return stagedFiles().map((f) => ({
		path: join(ROOT, f),
		text: execFileSync('git', ['show', `:${f}`], { cwd: ROOT, encoding: 'utf8' }),
	}));
}

function fileTargets(paths) {
	return paths.filter((p) => existsSync(p)).map((path) => ({ path, text: null }));
}

function main() {
	const args = process.argv.slice(2);
	if (args.length === 0) {
		process.stderr.write('사용: node scripts/validate-glossary.mjs <파일...> | --staged\n');
		process.exit(2);
	}

	const targets = args[0] === '--staged' ? stagedTargets() : fileTargets(args);

	const dict = loadGlossary();
	const violations = targets.flatMap(({ path, text }) => checkFile(path, dict, text));

	if (violations.length === 0) {
		process.stdout.write(`[용어집] 통과 — 검사 ${targets.length}개 파일\n`);
		return;
	}

	process.stderr.write('[용어집] 위반 — scripts/glossary/words.md에 등재 후 사전을 재생성하세요:\n');
	for (const v of violations) {
		const rel = v.path.replace(ROOT, '').replace(/^[\\/]/, '').replace(/\\/g, '/');
		process.stderr.write(`  ${rel}:${v.line}  ${v.kind} '${v.name}' — ${v.reason}\n`);
	}
	process.exit(1);
}

// 다른 스크립트가 이 파일을 모듈로 재사용할 수 있으므로,
// CLI로 직접 실행했을 때만 main을 돌린다.
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
	main();
}
