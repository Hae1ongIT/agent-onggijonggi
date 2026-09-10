import assert from 'node:assert/strict';
import test from 'node:test';

import {
	MIGRATION_DIRECTORY,
	isTimestampVersion,
	parseMigrationFileName,
	validateMigrationPaths,
} from './validate-flyway-migrations.mjs';

const migration = (name) => `${MIGRATION_DIRECTORY}/${name}`;

test('parses legacy and timestamp migration filenames', () => {
	assert.equal(parseMigrationFileName(migration('V14__thread_invitation.sql')).normalizedVersion, '14');
	assert.equal(
		parseMigrationFileName(migration('V20260910010203004__add_thread_title.sql')).description,
		'add_thread_title',
	);
	assert.equal(parseMigrationFileName(migration('invalid.sql')), null);
});

test('treats equivalent Flyway numeric versions as duplicates', () => {
	const result = validateMigrationPaths([
		migration('V1__first.sql'),
		migration('V001__same_flyway_version.sql'),
		migration('V1_0__same_trailing_zero_version.sql'),
	]);

	assert.equal(result.errors.length, 1);
	assert.match(result.errors[0], /Flyway version 1 is duplicated/);
});

test('keeps existing sequential migrations valid while requiring changed migrations to use timestamps', () => {
	const legacy = migration('V14__thread_invitation.sql');
	assert.deepEqual(validateMigrationPaths([legacy]).errors, []);

	const result = validateMigrationPaths([legacy], [legacy]);

	assert.deepEqual(result.errors, [
		`${legacy}: newly added or renamed migrations must use VYYYYMMDDHHmmssSSS__description.sql`,
	]);
});

test('reports malformed version separators without crashing', () => {
	const invalid = migration('V1.__broken_version.sql');
	const result = validateMigrationPaths([invalid]);

	assert.deepEqual(result.errors, [
		`${invalid}: Flyway versioned SQL filename must be V<version>__<description>.sql`,
	]);
});

test('accepts a valid changed timestamp migration', () => {
	const timestamp = migration('V20260910010203004__add_thread_title.sql');
	const result = validateMigrationPaths([migration('V14__thread_invitation.sql'), timestamp], [timestamp]);

	assert.deepEqual(result.errors, []);
});

test('rejects a changed timestamp-shaped migration with an invalid UTC date', () => {
	const invalid = migration('V20261310010203004__invalid_date.sql');
	const result = validateMigrationPaths([invalid], [invalid]);

	assert.equal(isTimestampVersion('20261310010203004'), false);
	assert.deepEqual(result.errors, [
		`${invalid}: newly added or renamed migrations must use VYYYYMMDDHHmmssSSS__description.sql`,
	]);
});
