#!/usr/bin/env python3
"""Pin every packaged card, visible option and keyword for the native answer parser."""
import base64
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[5]
raw = (root / 'app/src/main/assets/question-bank.json').read_bytes()
bank = json.loads(raw)
out = Path(__file__).resolve().parent
encode = lambda value: base64.b64encode(str(value or '').encode()).decode()
rows = []
for card in bank['questions']:
    fields = [card['id'], card['format'], card.get('correctChoiceId'), len(card['options'])]
    for option in card['options']:
        fields += [option['id'], option['label'], '\n'.join(option.get('keywords', []))]
    rows.append('\t'.join(map(encode, fields)))
(out / 'cards.tsv').write_text('\n'.join(rows) + '\n')
(out / 'snapshot.properties').write_text(f"bankSha256={hashlib.sha256(raw).hexdigest()}\nversion={bank['version']}\n")
print(f"{len(rows)} cards, {sum(len(c['options']) for c in bank['questions'])} choices")
