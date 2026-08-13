#!/usr/bin/env node
// 표준단어 목록(scripts/glossary/words.md)에 축약 알고리즘을 일괄 적용해
// 축약 사전(scripts/glossary/data-glossary.md)을 생성한다.
//
// 충돌(서로 다른 단어가 같은 축약)·불량(3자 미만, 동일 문자 반복)을 검출하면 생성을 중단한다(exit 1).
// 알고리즘이 모든 단어를 처리할 수는 없다는 전제이며, 실패분은 사람이 words.md 예외 테이블에 등재한다.
//
// 의존성 없음(node 내장만) — 저장소 훅·스크립트의 무설치 단독 실행 전제를 따른다.

import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const WORDS_PATH = join(ROOT, 'scripts', 'glossary', 'words.md');
const OUT_PATH = join(ROOT, 'scripts', 'glossary', 'data-glossary.md');

const VOWELS = new Set(['a', 'e', 'i', 'o', 'u']);
/** 이 길이 이하는 축약하지 않는다. PG 식별자 한도가 63바이트라 짧은 단어를 줄일 이유가 없다. */
const KEEP_AS_IS_MAX = 4;
/** 자음 골격에서 취할 자릿수. */
const ABBR_LEN = 3;

/**
 * 축약 알고리즘. 4자 이하는 원형, 5자 이상은 모음을 제거한 뒤 앞 3자를 취한다.
 * 첫 글자는 모음이어도 보존한다 — 없애면 update→pdt, index→ndx 처럼 읽을 수 없게 된다.
 */
export function abbreviate(word) {
	const w = word.toLowerCase();
	if (w.length <= KEEP_AS_IS_MAX) return w;

	let skeleton = '';
	for (let i = 0; i < w.length; i++) {
		if (i === 0 || !VOWELS.has(w[i])) skeleton += w[i];
	}
	return skeleton.slice(0, ABBR_LEN);
}

/**
 * 마크다운 표의 데이터 행만 셀 배열로 변환한다.
 * 첫 셀이 백틱 식별자인 행만 인정하므로 헤더·구분선은 자동으로 걸러진다.
 */
function tableRow(line) {
	if (!line.trim().startsWith('|')) return null;
	const cells = line.split('|').slice(1, -1).map((c) => c.trim());
	if (cells.length === 0) return null;

	const first = /^`([^`]+)`$/.exec(cells[0]);
	if (!first) return null;

	return [first[1], ...cells.slice(1).map(stripBacktick)];
}

function stripBacktick(s) {
	const m = /^`([^`]+)`$/.exec(s);
	return m ? m[1] : s;
}

/** words.md를 `## 단어` / `## 예외` 섹션으로 나눠 파싱한다. */
function parseWords(md) {
	const words = [];
	const exceptions = [];
	let section = null;

	for (const line of md.split(/\r?\n/)) {
		const heading = /^##\s+(.+?)\s*$/.exec(line);
		if (heading) {
			section = heading[1];
			continue;
		}

		const cells = tableRow(line);
		if (!cells) continue;

		if (section === '단어' && cells.length >= 2) {
			words.push({ word: cells[0], desc: cells[1] });
		} else if (section === '예외' && cells.length >= 3) {
			exceptions.push({ word: cells[0], abbr: cells[1], reason: cells[2] });
		}
	}
	return { words, exceptions };
}

/** 알고리즘 산출물이 쓸 수 없는 결과인지 판정한다. 예외로 등재되면 이 검사를 받지 않는다. */
function badReason(abbr) {
	if (abbr.length < ABBR_LEN) return `${ABBR_LEN}자 미만`;
	if (new Set(abbr).size === 1) return '동일 문자 반복';
	return null;
}

function build() {
	const { words, exceptions } = parseWords(readFileSync(WORDS_PATH, 'utf8'));
	const override = new Map(exceptions.map((e) => [e.word, e]));

	const entries = words.map(({ word, desc }) => {
		const ex = override.get(word);
		return ex
			? { word, desc, abbr: ex.abbr, source: '예외', reason: ex.reason }
			: { word, desc, abbr: abbreviate(word), source: '알고리즘', reason: '' };
	});

	// 단어 표에 없는 예외(관용어 등)도 유효한 토큰이므로 사전에 포함한다.
	for (const e of exceptions) {
		if (!entries.some((x) => x.word === e.word)) {
			entries.push({ word: e.word, desc: '', abbr: e.abbr, source: '예외', reason: e.reason });
		}
	}

	const problems = [];

	for (const e of entries) {
		// 원형 유지 단어(at·by 등)는 짧아도 정상이므로 축약된 것만 검사한다.
		if (e.source !== '알고리즘' || e.word.length <= KEEP_AS_IS_MAX) continue;
		const bad = badReason(e.abbr);
		if (bad) problems.push(`불량: ${e.word} → '${e.abbr}' (${bad})`);
	}

	const byAbbr = new Map();
	for (const e of entries) {
		if (!byAbbr.has(e.abbr)) byAbbr.set(e.abbr, []);
		byAbbr.get(e.abbr).push(e.word);
	}
	for (const [abbr, ws] of byAbbr) {
		if (ws.length > 1) problems.push(`충돌: ${ws.join(' / ')} → 모두 '${abbr}'`);
	}

	if (problems.length > 0) {
		process.stderr.write('[용어집] 생성 중단 — words.md 예외 테이블에 등재가 필요합니다:\n');
		for (const p of problems) process.stderr.write(`  - ${p}\n`);
		process.exit(1);
	}

	entries.sort((a, b) => a.word.localeCompare(b.word));
	writeFileSync(OUT_PATH, render(entries), 'utf8');

	const byAlgo = entries.filter((e) => e.source === '알고리즘').length;
	process.stdout.write(
		`[용어집] ${entries.length}개 생성 (알고리즘 ${byAlgo} / 예외 ${entries.length - byAlgo}) → scripts/glossary/data-glossary.md\n`,
	);
}

function render(entries) {
	const rows = entries
		.map((e) => `| \`${e.abbr}\` | \`${e.word}\` | ${e.desc || '-'} | ${e.source} | ${e.reason || '-'} |`)
		.join('\n');

	return `# 데이터 축약 사전 (생성물 — 직접 편집 금지)

> \`scripts/glossary/words.md\`에서 생성됩니다. 고칠 것이 있으면 그 파일을 고치고 \`node scripts/build-glossary.mjs\`를 다시 실행하세요.

DB 테이블·컬럼과 ES 필드 이름은 **snake_case**로 쓰고, \`_\`로 분해한 각 토큰이 아래 축약어여야 합니다.
조합은 자유이며 등재 대상이 아닙니다 — \`mbr\`와 \`id\`가 있으면 \`mbr_id\`는 그대로 성립합니다.

| 축약 | 원형 | 뜻 | 출처 | 비고 |
|---|---|---|---|---|
${rows}
`;
}

build();
