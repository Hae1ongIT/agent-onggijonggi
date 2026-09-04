import assert from 'node:assert/strict';
import test from 'node:test';

import { checkFile, inScope } from './validate-java-class-header.mjs';

test('accepts a type with both required header lines before its declaration', () => {
	const result = checkFile('Example.java', `
/**
 * Class Name : Example.java
 * Description : 테스트 타입이다.
 */
public class Example {}
`);

	assert.equal(result, null);
});

test('reports each missing header line', () => {
	const result = checkFile('Example.java', `
/**
 * Description : 테스트 타입이다.
 */
public record Example(String value) {}
`);

	assert.deepEqual(result, { path: 'Example.java', missing: ['Class Name :'] });
});

test('does not accept a header that appears after the type declaration', () => {
	const result = checkFile('Example.java', `
public class Example {}
/**
 * Class Name : Example.java
 * Description : 너무 늦은 헤더다.
 */
`);

	assert.deepEqual(result, { path: 'Example.java', missing: ['Class Name :', 'Description :'] });
});

test('does not treat a method-only Java file as a class header target', () => {
	const result = checkFile('package-info.java', '@Deprecated\npackage example;\n');

	assert.equal(result, null);
});

test('limits the target to Java files', () => {
	assert.equal(inScope('backend/common/bff-web/src/main/java/Example.java'), true);
	assert.equal(inScope('scripts/validate-java-class-header.mjs'), false);
});
