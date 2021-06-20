#!/usr/bin/env python
import os
import tempfile
import logging
import subprocess
import argparse
from collections import defaultdict, namedtuple
import music21


Note = namedtuple('Note', 'pitch offset duration')


def pianoroll(score):
    result = defaultdict(set)
    for measure in score.recurse().getElementsByClass('Measure'):
        measureBag = result[measure.number]
        for rest in measure.recurse().getElementsByClass('Rest'):
            measureBag.add(Note('rest', rest.offset, rest.quarterLength))
        for note in measure.recurse().getElementsByClass('Note'):
            measureBag.add(Note(note.pitch, note.offset, note.quarterLength))
        for chord in measure.recurse().getElementsByClass('Chord'):
            for pitch in chord.pitches:
                measureBag.add(Note(pitch, chord.offset, chord.quarterLength))
    return result


def diffMeasures(measure1, measure2):
    common = measure1 & measure2
    left = measure1 - measure2
    right = measure2 - measure1
    logging.debug('Common: {}, Left: {}, Right: {}'.format(common, left, right))
    score = len(common) - len(left) - len(right)
    logging.debug('Score: %s', score)
    return score


def diffRolls(roll1, roll2):
    score = 0
    barNumbers = sorted(set(roll1.keys()) | set(roll2.keys()))
    for n in barNumbers:
        logging.debug('Measure %d', n)
        score += diffMeasures(roll1.get(n, set()), roll2.get(n, set()))
    return score


def diffFiles(files):
    rolls = [pianoroll(music21.converter.parseFile(f)) for f in files]
    score = diffRolls(rolls[0], rolls[1])
    logging.info('Score for {}: {}'.format(files, score))
    return score


def find_file(dir, pattern):
    head, _, tail = pattern.partition('*')
    for d, _, files in os.walk(dir):
        for f in files:
            if f.startswith(head) and f.endswith(tail):
                return os.path.abspath(os.path.join(d, f))
    raise Exception('file matching "{}*" not found in {}'.format(pattern, dir))


def processCases(args):
    score = 0
    for case in os.listdir(args.cases):
        logging.info('Processing case ' + case)
        casedir = os.path.join(args.cases, case)
        source = find_file(casedir, 'source*')
        target = find_file(casedir, 'target*')
        with tempfile.TemporaryDirectory() as tmpdir:
            script = args.script.replace('$@', tmpdir).replace('$<', source)
            logging.debug('Calling script: ' + script)
            subprocess.check_call(script.split(), cwd='..')
            result = find_file(tmpdir, '*.mxl')
            score += diffFiles((result, target))
    return score


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('-c', '--cases', help='directory with test cases')
    parser.add_argument('-v', '--verbose', action='store_true')
    parser.add_argument('--script', help='command to recognize a given score',
                        default='gradle run -PcmdLineArgs=-batch,-export,-output,$@,--,$<')
    args = parser.parse_args()

    if args.verbose:
        logging.getLogger().setLevel(logging.DEBUG)

    score = processCases(args)
    print('Total score:', score)


if __name__ == '__main__':
    main()
