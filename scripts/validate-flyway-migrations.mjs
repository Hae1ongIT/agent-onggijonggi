#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import { readdirSync } from 'node:fs';
import { basename, dirname, join, relative, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
export const MIGRATION_DIRECTORY = 'backend/common/src/main/resources/db/migration';
const MIGRATION_SUFFIX = '.sql';
const VERSIONED_MIGRATION = /^V(\d+(?:[._]\d+)*)__([^/\\]+)\.sql$/;
const TIMESTAMP_VERSION = /^\d{17}$/;

function repositoryPath(path) {
	return path.replace(/\\/g, '/');
}

function migrationPath(path) {
	return repositoryPath(path).startsWith(`${MIGRATION_DIRECTORY}/`);
}

function parseUtcTimestamp(version) {
	if (!TIMESTAMP_VERSION.test(version)) return null;

	const year = Number(version.slice(0, 4));
	const month = Number(version.slice(4, 6));
	const day = Number(version.slice(6, 8));
	const hour = Number(version.slice(8, 10));
	const minute = Number(version.slice(10, 12));
	const second = Number(version.slice(12, 14));
	const millisecond = Number(version.slice(14, 17));
	const value = Date.UTC(year, month - 1, day, hour, minute, second, millisecond);
	const date = new Date(value);

	return date.getUTCFullYear() === year
		&& date.getUTCMonth() === month - 1
		&& date.getUTCDate() === day
		&& date.getUTCHours() === hour
		&& date.getUTCMinutes() === minute
		&& date.getUTCSeconds() === second
		&& date.getUTCMilliseconds() === millisecond
		? value
		: null;
}

export function parseMigrationFileName(path) {
	const fileName = basename(repositoryPath(path));
	const match = VERSIONED_MIGRATION.exec(fileName);
	if (match == null) return null;

	const [, version, description] = match;
	const normalizedParts = version
		.replace(/[._]/g, '.')
		.split('.')
		.map((part) => BigInt(part));
	while (normalizedParts.length > 1 && normalizedParts.at(-1) === 0n) normalizedParts.pop();

	return {
		path: repositoryPath(path),
		fileName,
		version,
		description,
		normalizedVersion: normalizedParts.map(String).join('.'),
		timestampMillis: parseUtcTimestamp(version),
	};
}

export function isTimestampVersion(version) {
	return parseUtcTimestamp(version) != null;
}

export function validateMigrationPaths(paths, changedPaths = []) {
	const migrationFiles = paths
		.map(repositoryPath)
		.filter((path) => migrationPath(path))
		.filter((path) => path.endsWith(MIGRATION_SUFFIX));
	const errors = [];
	const parsed = [];

	for (const path of migrationFiles) {
		const migration = parseMigrationFileName(path);
		if (migration == null) {
			errors.push(`${path}: Flyway versioned SQL filename must be V<version>__<description>.sql`);
			continue;
		}
		parsed.push(migration);
	}

	const versions = new Map();
	for (const migration of parsed) {
		const matchingPaths = versions.get(migration.normalizedVersion) ?? [];
		matchingPaths.push(migration.path);
		versions.set(migration.normalizedVersion, matchingPaths);
	}
	for (const [version, matchingPaths] of versions) {
		if (matchingPaths.length > 1) {
			errors.push(`Flyway version ${version} is duplicated: ${matchingPaths.join(', ')}`);
		}
	}

	const changed = new Set(changedPaths.map(repositoryPath).filter(migrationPath));
	for (const migration of parsed) {
		if (changed.has(migration.path) && !isTimestampVersion(migration.version)) {
			errors.push(`${migration.path}: newly added or renamed migrations must use VYYYYMMDDHHmmssSSS__description.sql`);
		}
	}

	return { errors, migrationFiles, parsed };
}

function runGit(args) {
	return execFileSync('git', args, { cwd: ROOT, encoding: 'utf8' });
}

function trackedMigrationPaths() {
	return runGit(['ls-files'])
		.split(/\r?\n/)
		.filter(Boolean)
		.filter((path) => migrationPath(path) && path.endsWith(MIGRATION_SUFFIX));
}

function parseNameStatus(output) {
	const fields = output.split('\0');
	const changed = [];
	for (let index = 0; index < fields.length - 1; index += 1) {
		const status = fields[index];
		if (!status) continue;
		const kind = status[0];
		if (kind === 'R' || kind === 'C') {
			index += 2;
			changed.push(fields[index]);
			continue;
		}
		index += 1;
		if (kind === 'A') changed.push(fields[index]);
	}
	return changed.filter(Boolean);
}

function stagedChangedPaths() {
	return parseNameStatus(runGit(['diff', '--cached', '--name-status', '-z', '--find-renames']));
}

function changedPathsSince(base) {
	return parseNameStatus(runGit(['diff', '--name-status', '-z', '--find-renames', `${base}...HEAD`]));
}

function walkSqlFiles(directory, paths = []) {
	for (const entry of readdirSync(directory, { withFileTypes: true })) {
		const path = join(directory, entry.name);
		if (entry.isDirectory()) walkSqlFiles(path, paths);
		else if (entry.isFile() && entry.name.endsWith(MIGRATION_SUFFIX)) paths.push(repositoryPath(relative(ROOT, path)));
	}
	return paths;
}

function workingTreeMigrationPaths() {
	return walkSqlFiles(join(ROOT, MIGRATION_DIRECTORY));
}

function usage() {
	process.stderr.write('Usage: node scripts/validate-flyway-migrations.mjs --staged | --tracked | --changed-from <base-sha>\n');
}

function main() {
	const args = process.argv.slice(2);
	let paths;
	let changedPaths;
	if (args.length === 1 && args[0] === '--staged') {
		paths = trackedMigrationPaths();
		changedPaths = stagedChangedPaths();
	} else if (args.length === 1 && args[0] === '--tracked') {
		paths = workingTreeMigrationPaths();
		changedPaths = [];
	} else if (args.length === 2 && args[0] === '--changed-from') {
		paths = workingTreeMigrationPaths();
		changedPaths = changedPathsSince(args[1]);
	} else {
		usage();
		process.exit(2);
	}

	const { errors, migrationFiles } = validateMigrationPaths(paths, changedPaths);
	if (errors.length === 0) {
		process.stdout.write(`[Flyway migration] OK: checked ${migrationFiles.length} SQL files\n`);
		return;
	}

	process.stderr.write('[Flyway migration] failed:\n');
	for (const error of errors) process.stderr.write(`  ${error}\n`);
	process.exit(1);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
	main();
}
