import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import test from 'node:test';

import {
	createMigration,
	formatUtcTimestamp,
	nextTimestampVersion,
	renumberMigration,
} from './flyway-migration.mjs';
import { MIGRATION_DIRECTORY } from './validate-flyway-migrations.mjs';

test('chooses the next UTC millisecond after an existing timestamp migration', () => {
	const existing = `${MIGRATION_DIRECTORY}/V20260910010203004__existing.sql`;
	const now = Date.UTC(2026, 8, 10, 1, 2, 3, 4);

	assert.equal(nextTimestampVersion([existing], now), '20260910010203005');
	assert.equal(formatUtcTimestamp(now), '20260910010203004');
});

test('creates a migration with the standard header and a timestamp filename', () => {
	const root = mkdtempSync(join(tmpdir(), 'flyway-migration-'));
	try {
		const path = createMigration('add_thread_title', { root, now: Date.UTC(2026, 8, 10, 1, 2, 3, 4) });

		assert.match(path.replace(/\\/g, '/'), /V20260910010203004__add_thread_title\.sql$/);
		assert.match(readFileSync(path, 'utf8'), /변경 이유:/);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

test('requires confirmation and preserves migration contents when renumbering', () => {
	const root = mkdtempSync(join(tmpdir(), 'flyway-migration-'));
	const directory = join(root, MIGRATION_DIRECTORY);
	const source = join(directory, 'V14__thread_invitation.sql');
	try {
		createMigration('seed', { root, now: Date.UTC(2026, 8, 10, 1, 2, 3, 4) });
		writeFileSync(source, '-- original migration\n', 'utf8');

		assert.throws(() => renumberMigration(source, { root }), /confirm-not-permanently-applied/);
		const target = renumberMigration(source, {
			root,
			now: Date.UTC(2026, 8, 10, 1, 2, 3, 4),
			confirmed: true,
		});

		assert.match(target.replace(/\\/g, '/'), /V20260910010203005__thread_invitation\.sql$/);
		assert.equal(existsSync(source), false);
		assert.equal(readFileSync(target, 'utf8'), '-- original migration\n');
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});
