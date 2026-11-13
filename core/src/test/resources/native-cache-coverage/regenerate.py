#!/usr/bin/env python3
"""Snapshot exact packaged card fields and the full target speech catalog for native factory regression."""
import argparse
import base64
import hashlib
import json
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--catalog', type=Path, required=True, help='Full target catalog.json, not partial generated metadata')
parser.add_argument('--bank', type=Path, default=Path(__file__).resolve().parents[5] / 'app/src/main/assets/question-bank.json')
args = parser.parse_args()
output = Path(__file__).resolve().parent
bank_raw = args.bank.read_bytes()
catalog_raw = args.catalog.read_bytes()
bank = json.loads(bank_raw)
catalog = json.loads(catalog_raw)
def encoded(value):
    return base64.b64encode(str(value if value is not None else '').encode('utf-8')).decode('ascii')
rows = []
for card in bank['questions']:
    options = card['options']
    correct = card.get('correctChoiceId')
    replies = card.get('replyByChoice', {})
    if card['format'] == 'quiz':
        correct_index = next(i for i, option in enumerate(options) if option['id'] == correct)
        replies = {option['id']: (f"맞아요. {card['explanation']}" if option['id'] == correct
            else f"정답은 {correct_index + 1}번이에요. {card['explanation']}") for option in options}
    fields = [card['id'], card['format'], card.get('title', ''), card['question'], correct,
        card.get('explanation', ''), card.get('sourceTitle', ''), card.get('sourceUrl', ''),
        card.get('sourceKind', ''), card.get('contentNote', ''), card.get('difficulty', 2), bank['version'], len(options)]
    for option in options:
        fields.extend([option['id'], option['label'], replies.get(option['id'], '')])
    rows.append('\t'.join(map(encoded, fields)))
(output / 'cards.tsv').write_text('\n'.join(rows) + '\n', encoding='utf-8')
(output / 'catalog.tsv').write_text('\n'.join('\t'.join(map(encoded, [clip['text'], clip['file']]))
    for clip in catalog['chunks']) + '\n', encoding='utf-8')
metadata = dict(bankVersion=bank['version'], bankSha256=hashlib.sha256(bank_raw).hexdigest(),
    catalogSha256=hashlib.sha256(catalog_raw).hexdigest(), cards=len(rows),
    quiz=sum(card['format'] == 'quiz' for card in bank['questions']),
    balance=sum(card['format'] == 'balance' for card in bank['questions']), catalogEntries=len(catalog['chunks']))
(output / 'snapshot.properties').write_text('\n'.join(f'{key}={value}' for key, value in metadata.items()) + '\n', encoding='utf-8')
print(json.dumps(metadata, ensure_ascii=False))
