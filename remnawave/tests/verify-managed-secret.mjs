import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

const contextLiteral = String.raw`AetherLink X Remnawave user secret v1\u0000`;
const password = 'test-remnawave-ss-password';
const expected = '9eu5ZF28FyW247-u-SrX4uaT1XzpvnoeAKq3KYqYylw';
const actual = createHash('sha256')
    .update('AetherLink X Remnawave user secret v1\0', 'utf8')
    .update(password, 'utf8')
    .digest('base64url');

if (actual !== expected) {
    throw new Error(`managed ALX secret KDF mismatch: ${actual}`);
}

for (const patchPath of [
    'remnawave/backend-managed-alx-2.7.4.patch',
    'remnawave/node-managed-alx-3.2.2.patch',
]) {
    const patch = readFileSync(patchPath, 'utf8');
    if (!patch.includes(contextLiteral)) {
        throw new Error(`${patchPath} does not contain the managed ALX KDF context`);
    }
}

console.log('managed ALX secret KDF vector OK');
