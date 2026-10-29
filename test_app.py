"""Check the packaged application's CLI, catalogs, and JSON configurations."""
import argparse
import json
import os
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('app', help='Packaged application image path')
args = parser.parse_args()
app = Path(args.app).resolve()
if sys.platform == 'darwin':
    binary = app / 'Contents/MacOS/Java Usage Generator'
elif os.name == 'nt':
    binary = app / 'jug.exe'
else:
    raise SystemExit('The packaged application test supports macOS and Windows.')
modular_jar = app.parents[1] / 'java-usage-generator.jar'
root = Path(__file__).resolve().parent
resources = root / 'src/main/resources'
catalog = json.loads((resources / 'catalog.json').read_text())
application_catalog = json.loads((resources / 'applications.json').read_text())
host_catalog = json.loads((resources / 'hosts.json').read_text())
os_version_catalog = json.loads((resources / 'os-versions.json').read_text())

with zipfile.ZipFile(modular_jar) as archive:
    bundled_applications = json.loads(archive.read('applications.json'))
    bundled_hosts = json.loads(archive.read('hosts.json'))
    bundled_os_versions = json.loads(archive.read('os-versions.json'))
assert bundled_applications == application_catalog
assert all('sourceRecords' not in entry for entry in bundled_applications['applications'])
assert bundled_hosts == host_catalog
assert len(bundled_hosts['hosts']) == 300
assert all('sourceRecords' not in entry for entry in bundled_hosts['hosts'])
assert bundled_os_versions == os_version_catalog
assert len(bundled_os_versions['osVersions']) == 7
assert all('sourceRecords' not in entry for entry in bundled_os_versions['osVersions'])

def run(*args):
    command = [str(binary)]
    if '--catalog' not in args:
        command.extend(('--catalog', str(resources / 'catalog.json')))
    return subprocess.run([*command, *args], capture_output=True, text=True)

entries = [entry for family in catalog['releases'] for entry in family['versions']]
expected_version_count = len(entries)

assert run('--help').returncode == 0
assert len(run('--list').stdout.splitlines()) == expected_version_count + 1
all_versions = run('--all')
assert all_versions.returncode == 0, all_versions.stderr
settings = json.loads(all_versions.stdout)
assert settings['versionCount'] == expected_version_count
for index, entry in enumerate(entries):
    assert settings['versions'][index]['version'] == entry['version']
    assert settings['versions'][index]['javaVersion'] == entry['javaVersion']

with tempfile.TemporaryDirectory(prefix='java-usage-generator-') as directory:
    output = Path(directory) / 'versions.json'
    result = run('--family', '8', '--version', '17.0.3.1', '--exclude-version', '8u5', '--output', str(output))
    assert result.returncode == 0, result.stderr
    assert run('--version', '27', '--output', str(output)).returncode != 0
    revised = run('--input', str(output), '--exclude-family', '8', '--version', '21')
    assert revised.returncode == 0, revised.stderr
    settings = json.loads(revised.stdout)
    assert settings['versionCount'] == 2
    assert settings['versions'][0]['version'] == '17.0.3.1'
    assert settings['versions'][1]['version'] == '21'
    assert run('--input', str(output), '--output', str(output), '--force').returncode != 0
    bad = Path(directory) / 'bad.json'
    bad.write_text(revised.stdout.replace('"javaVersion": "17.0.3.1"', '"javaVersion": "17.0.99"'))
    assert run('--input', str(bad)).returncode != 0
    assert run('--input', str(root / 'example-generator-versions.json')).returncode == 0
    assert run('--catalog', str(resources / 'catalog.json'), '--all').stdout == all_versions.stdout
    assert run('--family', '8', '--exclude-family', '8').returncode != 0
    assert run('--version', 'unknown').returncode != 0
    assert run('--family', '999').returncode != 0
print('Passed packaged CLI, all catalog mappings, bundled catalogs, JSON configuration reload, includes/excludes, invalid data, and overwrite protection.')
