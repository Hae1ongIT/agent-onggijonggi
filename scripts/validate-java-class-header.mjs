#!/usr/bin/env node
// Java 타입 선언 앞의 Javadoc에 Class Name/Description 헤더가 있는지 검사한다.
//
// 사용:
//   node scripts/validate-java-class-header.mjs <파일...>
//   node scripts/validate-java-class-header.mjs --staged
//   node scripts/validate-java-class-header.mjs --tracked

import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const TYPE_DECLARATION = /^\s*(?:(?:public|protected|private|abstract|final|sealed|non-sealed|static)\s+)*(?:class|interface|record|enum|@interface)\s+[A-Za-z_$][\w$]*/m;
const CLASS_NAME_HEADER = /^\s*\*\s*Class Name\s*:/m;
const DESCRIPTION_HEADER = /^\s*\*\s*Description\s*:/m;
const HEADER_EXAMPLE = [
	'/**',
	' * Class Name : ChatController.java',
	' * Description : 01·CLIENT ↔ 03·CORE 채팅 스트리밍·이력 조회 계약 구현체.',
	' */',
].join('\n');

export function inScope(path) {
	return path.replace(/\\/g, '/').endsWith('.java');
}

export function checkFile(path, text = null) {
	const content = text ?? readFileSync(path, 'utf8');
	const declaration = TYPE_DECLARATION.exec(content);
	if (declaration == null) return null;

	const headerArea = content.slice(0, declaration.index);
	const missing = [];
	if (!CLASS_NAME_HEADER.test(headerArea)) missing.push('Class Name :');
	if (!DESCRIPTION_HEADER.test(headerArea)) missing.push('Description :');
	return missing.length === 0 ? null : { path, missing };
}

function stagedTargets() {
	const out = execFileSync('git', ['diff', '--cached', '--name-only', '--diff-filter=ACM'], {
		cwd: ROOT,
		encoding: 'utf8',
	});
	return out.split(/\r?\n/).filter(Boolean).filter(inScope).map((file) => ({
		path: join(ROOT, file),
		text: execFileSync('git', ['show', `:${file}`], { cwd: ROOT, encoding: 'utf8' }),
	}));
}

function trackedTargets() {
	const out = execFileSync('git', ['ls-files', '--', '*.java'], { cwd: ROOT, encoding: 'utf8' });
	return out.split(/\r?\n/).filter(Boolean).filter(inScope).map((file) => ({
		path: join(ROOT, file),
		text: null,
	}));
}

function fileTargets(paths) {
	return paths.filter((path) => existsSync(path)).filter(inScope).map((path) => ({ path, text: null }));
}

function main() {
	const args = process.argv.slice(2);
	if (args.length === 0) {
		process.stderr.write('사용: node scripts/validate-java-class-header.mjs <파일...> | --staged | --tracked\n');
		process.exit(2);
	}

	const targets = args[0] === '--staged' ? stagedTargets()
		: args[0] === '--tracked' ? trackedTargets()
			: fileTargets(args);
	const violations = targets.map(({ path, text }) => checkFile(path, text)).filter(Boolean);
	if (violations.length === 0) {
		process.stdout.write(`[자바 헤더] 통과 — 검사 ${targets.length}개 파일\n`);
		return;
	}

	process.stderr.write('[자바 헤더] 위반 — CONTRIBUTING.md "코드 스타일"의 헤더를 추가하세요:\n');
	for (const violation of violations) {
		const relativePath = violation.path.replace(ROOT, '').replace(/^[\\/]/, '').replace(/\\/g, '/');
		process.stderr.write(`  ${relativePath}: ${violation.missing.join(', ')} 누락\n`);
	}
	process.stderr.write(`${HEADER_EXAMPLE}\n`);
	process.exit(1);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
	main();
}
