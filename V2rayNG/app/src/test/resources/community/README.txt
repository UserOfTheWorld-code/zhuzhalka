BIP340 official test vectors, copied without modification from:
https://raw.githubusercontent.com/bitcoin/bips/200f9b26fe0a2f235a2af8b30c4be9f12f6bc9cb/bip-0340/test-vectors.csv
Retrieved 2026-10-30. SHA256:
34c9d1d9c3a88d524bc80778540dc43f8306ec249a7485293063c376db851c2d

BIP340 test vectors are available under CC0 1.0 (chosen here), BSD-2-Clause,
or MIT at the recipient's option. License statement:
https://github.com/bitcoin/bips/blob/master/bip-0340.mediawiki#test-vectors-and-reference-code

CommunityCrypto intentionally signs and verifies only 32-byte Nostr event hashes.
Vectors 0..14 test BIP340 acceptance/rejection and deterministic signatures.
Vectors 15..18 have non-32-byte messages: our Nostr-specific boundary rejects them.
