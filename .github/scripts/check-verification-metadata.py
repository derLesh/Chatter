#!/usr/bin/env python3
"""Decides whether a regenerated gradle/verification-metadata.xml may be committed unattended.

The file is trust policy as well as a list of checksums: one line added to <configuration> can
switch verification off, and one <sha256> or <also-trust> added to a known artifact makes a second
file acceptable under the same name. Counting removed lines sees none of that, so this reads both
files and allows exactly one kind of change: checksums for components or artifacts that were not
there before. Anything else is left to a person.

    check-verification-metadata.py <committed file> <regenerated file>

Exits 0 when the regenerated file may replace the committed one, 1 with the reasons when not.
"""

import re
import sys
import xml.etree.ElementTree as ElementTree

NS = '{https://schema.gradle.org/dependency-verification}'
SHA256 = re.compile(r'^[0-9a-f]{64}$')


class Refused(Exception):
    pass


def read(path):
    with open(path, 'rb') as f:
        raw = f.read()
    # Gradle writes neither; an entity could expand or reach outside the file.
    if b'<!DOCTYPE' in raw or b'<!ENTITY' in raw:
        raise Refused('%s declares a DOCTYPE or an entity' % path)
    try:
        return ElementTree.fromstring(raw)
    except ElementTree.ParseError as e:
        raise Refused('%s is not well-formed XML: %s' % (path, e))


def canonical(element):
    """The element as data, so two files compare equal whatever their indentation."""
    return (
        element.tag,
        tuple(sorted(element.attrib.items())),
        (element.text or '').strip(),
        tuple(canonical(child) for child in element),
    )


def sections(root, path):
    if root.tag != NS + 'verification-metadata':
        raise Refused('%s: the root is <%s>, not <verification-metadata>' % (path, root.tag))
    tags = [child.tag for child in root]
    if tags != [NS + 'configuration', NS + 'components']:
        raise Refused('%s: expected <configuration> and <components> only, found %s' % (path, tags))
    return root[0], root[1]


def components(element, path):
    """The <component> entries by (group, name, version); a key that appears twice is refused."""
    found = {}
    for component in element:
        if component.tag != NS + 'component':
            raise Refused('%s: <components> holds a <%s>' % (path, component.tag))
        if set(component.attrib) != {'group', 'name', 'version'}:
            raise Refused('%s: a component has the attributes %s' % (path, sorted(component.attrib)))
        key = (component.get('group'), component.get('name'), component.get('version'))
        if key in found:
            raise Refused('%s: %s:%s:%s is listed twice' % ((path,) + key))
        found[key] = component
    return found


def artifacts(component, path, key):
    found = {}
    for artifact in component:
        if artifact.tag != NS + 'artifact' or set(artifact.attrib) != {'name'}:
            raise Refused('%s: %s:%s:%s holds an unexpected <%s>' % ((path,) + key + (artifact.tag,)))
        name = artifact.get('name')
        if name in found:
            raise Refused('%s: %s:%s:%s lists %s twice' % ((path,) + key + (name,)))
        found[name] = artifact
    return found


def check_new_artifact(artifact, key):
    """A new artifact is one SHA-256 and nothing else: no other hash, key, or exemption."""
    where = '%s:%s:%s %s' % (key + (artifact.get('name'),))
    children = list(artifact)
    if len(children) != 1 or children[0].tag != NS + 'sha256':
        raise Refused('%s: a new artifact must carry exactly one <sha256>, found %s'
                      % (where, [c.tag for c in children]))
    sha = children[0]
    if not set(sha.attrib) <= {'value', 'origin'} or len(sha) or (sha.text or '').strip():
        raise Refused('%s: its <sha256> has more than a value and an origin' % where)
    if not SHA256.match(sha.get('value') or ''):
        raise Refused('%s: "%s" is not a SHA-256' % (where, sha.get('value')))


def compare(committed_path, regenerated_path):
    """Returns the number of artifacts added; raises Refused when the change is not only that."""
    committed_config, committed_components = sections(read(committed_path), committed_path)
    regenerated_config, regenerated_components = sections(read(regenerated_path), regenerated_path)

    if canonical(committed_config) != canonical(regenerated_config):
        raise Refused('<configuration> changed')

    before = components(committed_components, committed_path)
    after = components(regenerated_components, regenerated_path)

    gone = sorted(set(before) - set(after))
    if gone:
        raise Refused('components disappeared: %s' % ', '.join(':'.join(k) for k in gone))

    added = 0
    for key, component in after.items():
        new = artifacts(component, regenerated_path, key)
        old = artifacts(before[key], committed_path, key) if key in before else {}
        for name, artifact in old.items():
            if name not in new:
                raise Refused('%s:%s:%s %s disappeared' % (key + (name,)))
            if canonical(artifact) != canonical(new[name]):
                raise Refused('%s:%s:%s %s changed its checksums' % (key + (name,)))
        for name, artifact in new.items():
            if name not in old:
                check_new_artifact(artifact, key)
                added += 1
        if not new:
            raise Refused('%s:%s:%s has no artifacts' % key)
    return added


def main(argv):
    if len(argv) != 3:
        print('usage: check-verification-metadata.py <committed file> <regenerated file>', file=sys.stderr)
        return 2
    try:
        added = compare(argv[1], argv[2])
    except Refused as e:
        print('::error::The regenerated verification metadata does more than add checksums: %s' % e)
        return 1
    print('Checksums for %d new artifacts, nothing else changed.' % added)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
