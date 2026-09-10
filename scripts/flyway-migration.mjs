#!/usr/bin/env node

import { existsSync, mkdirSync, readdirSync, renameSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

import { MIGRATION_DIRECTORY, parseMigrationFileName } from './validate-flyway-migrations.mjs';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const DESCRIPTION = /^[a-z0-9]+(?:_[a-z0-9]+)*$/;

export function formatUtcTimestamp(milliseconds) {
	const date = new Date(milliseconds);
	const pad = (value, length) => String(value).padStart(length, '0');
	return `${pad(date.getUTCFullYear(), 4)}${pad(date.getUTCMonth() + 1, 2)}${pad(date.getUTCDate(), 2)}${pad(date.getUTCHours(), 2)}${pad(date.getUTCMinutes(), 2)}${pad(date.getUTCSeconds(), 2)}${pad(date.getUTCMilliseconds(), 3)}`;
}

function timestampMillis(migration) {
	return migration.timestampMillis == null ? null : migration.timestampMillis;
}

export function nextTimestampVersion(paths, now = Date.now()) {
	const latest = paths
		.map(parseMigrationFileName)
		.filter(Boolean)
		.map(timestampMillis)
		.filter((value) => value != null)
		.reduce((maximum, value) => Math.max(maximum, value), Number.NEGATIVE_INFINITY);
	return formatUtcTimestamp(Math.max(now, Number.isFinite(latest) ? latest + 1 : now));
}

function migrationDirectory(root = ROOT) {
	return join(root, MIGRATION_DIRECTORY);
}

function migrationPaths(directory) {
	return readdirSync(directory)
		.filter((name) => name.endsWith('.sql'))
		.map((name) => join(MIGRATION_DIRECTORY, name));
}

function assertDescription(description) {
	if (!DESCRIPTION.test(description)) {
		throw new Error('description must use lowercase letters, digits, and underscores (for example: add_thread_title)');
	}
}

function assertInsideMigrationDirectory(path, directory) {
	const relativePath = relative(directory, path);
	if (relativePath.startsWith(`..${sep}`) || relativePath === '..' || relativePath === '') {
		throw new Error('migration path must be inside the configured migration directory');
	}
}

const TEMPLATE = `-- 04·DATA\n-- 변경 이유:\n-- 기존 데이터 전제:\n-- 이관·중단 조건:\n\n`;

export function createMigration(description, { root = ROOT, now = Date.now() } = {}) {
	assertDescription(description);
	const directory = migrationDirectory(root);
	mkdirSync(directory, { recursive: true });
	const version = nextTimestampVersion(migrationPaths(directory), now);
	const path = join(directory, `V${version}__${description}.sql`);
	if (existsSync(path)) throw new Error(`migration already exists: ${path}`);
	writeFileSync(path, TEMPLATE, { encoding: 'utf8', flag: 'wx' });
	return path;
}

export function renumberMigration(path, { root = ROOT, now = Date.now(), confirmed = false } = {}) {
	if (!confirmed) throw new Error('renumber requires --confirm-not-permanently-applied');
	const directory = migrationDirectory(root);
	const absolutePath = resolve(ROOT, path);
	assertInsideMigrationDirectory(absolutePath, directory);
	if (!existsSync(absolutePath)) throw new Error(`migration does not exist: ${absolutePath}`);

	const migration = parseMigrationFileName(relative(root, absolutePath));
	if (migration == null) throw new Error('migration filename must be V<version>__<description>.sql');
	const version = nextTimestampVersion(migrationPaths(directory), now);
	const target = join(directory, `V${version}__${migration.description}.sql`);
	renameSync(absolutePath, target);
	return target;
}

function usage() {
	process.stderr.write('Usage:\n');
	process.stderr.write('  node scripts/flyway-migration.mjs create <lowercase_snake_case_description>\n');
	process.stderr.write('  node scripts/flyway-migration.mjs renumber <migration-path> --confirm-not-permanently-applied\n');
}

function main() {
	const args = process.argv.slice(2);
	try {
		if (args.length === 2 && args[0] === 'create') {
			const path = createMigration(args[1]);
			process.stdout.write(`Created ${relative(ROOT, path).replace(/\\/g, '/')}\n`);
			return;
		}
		if (args.length === 3 && args[0] === 'renumber' && args[2] === '--confirm-not-permanently-applied') {
			const path = renumberMigration(args[1], { confirmed: true });
			process.stdout.write(`Renumbered ${relative(ROOT, path).replace(/\\/g, '/')}\n`);
			return;
		}
		usage();
		process.exit(2);
	} catch (error) {
		process.stderr.write(`[Flyway migration] ${error.message}\n`);
		process.exit(1);
	}
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
	main();
}
