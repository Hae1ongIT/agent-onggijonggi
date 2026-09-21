#!/usr/bin/env node
import { execFileSync } from 'node:child_process';

function git(args) {
  return execFileSync('git', args, { encoding: 'utf8' }).trim();
}

function compareSemver(a, b) {
  const pa = a.slice(1).split('.').map(Number);
  const pb = b.slice(1).split('.').map(Number);
  for (let i = 0; i < 3; i += 1) {
    if (pa[i] !== pb[i]) return pa[i] - pb[i];
  }
  return 0;
}

const stableTags = git(['tag', '--merged', 'HEAD', '--list', 'v*'])
  .split('\n')
  .filter((tag) => /^v\d+\.\d+\.\d+$/.test(tag))
  .sort(compareSemver);

const lastTag = stableTags.at(-1) ?? 'v0.0.0';
const range = stableTags.length ? `${lastTag}..HEAD` : 'HEAD';

const hashes = git(['log', range, '--format=%H']).split('\n').filter(Boolean);

let bump = 'patch';
for (const hash of hashes) {
  const subject = git(['log', '-1', '--format=%s', hash]);
  const body = git(['log', '-1', '--format=%B', hash]);
  const isBreaking = /^[a-zA-Z]+(\([^)]*\))?!:/.test(subject) || /^BREAKING CHANGE:/m.test(body);
  if (isBreaking) {
    bump = 'major';
    break;
  }
  if (/^feat(\([^)]*\))?:/.test(subject)) {
    bump = 'minor';
  }
}

const [major, minor, patch] = lastTag.slice(1).split('.').map(Number);
const next =
  bump === 'major'
    ? [major + 1, 0, 0]
    : bump === 'minor'
      ? [major, minor + 1, 0]
      : [major, minor, patch + 1];

process.stdout.write(
  JSON.stringify({ lastTag, bump, nextVersion: `v${next.join('.')}` }),
);
