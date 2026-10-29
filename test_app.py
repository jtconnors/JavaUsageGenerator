"""Check the packaged application's CLI, catalog, and properties compatibility."""
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
host_catalog = json.loads((resources / 'hosts.json').read_text())
os_version_catalog = json.loads((resources / 'os-versions.json').read_text())

with zipfile.ZipFile(modular_jar) as archive:
    bundled_hosts = json.loads(archive.read('hosts.json'))
    bundled_os_versions = json.loads(archive.read('os-versions.json'))
assert bundled_hosts == host_catalog
assert len(bundled_hosts['hosts']) == 300
assert sum(entry['sourceRecords'] for entry in bundled_hosts['hosts']) == 840
assert bundled_os_versions == os_version_catalog
assert len(bundled_os_versions['osVersions']) == 7
assert sum(entry['sourceRecords'] for entry in bundled_os_versions['osVersions']) == 840

def run(*args):
    return subprocess.run([str(binary), *args], capture_output=True, text=True)

def properties(text):
    return dict(line.split('=', 1) for line in text.splitlines() if line and not line.startswith('#'))

entries = [entry for family in catalog['releases'] for entry in family['versions']]
expected_version_count = len(entries)

assert run('--help').returncode == 0
assert len(run('--list').stdout.splitlines()) == expected_version_count + 1
all_versions = run('--all')
assert all_versions.returncode == 0, all_versions.stderr
settings = properties(all_versions.stdout)
assert settings['generator.selection.count'] == str(expected_version_count)
for index, entry in enumerate(entries):
    assert settings[f'generator.selection.{index}.version'] == entry['version']
    assert settings[f'generator.selection.{index}.javaVersion'] == entry['javaVersion']

with tempfile.TemporaryDirectory(prefix='java-usage-generator-') as directory:
    output = Path(directory) / 'versions.properties'
    result = run('--family', '8', '--version', '17.0.3.1', '--exclude-version', '8u5', '--output', str(output))
    assert result.returncode == 0, result.stderr
    assert run('--version', '27', '--output', str(output)).returncode != 0
    revised = run('--input', str(output), '--exclude-family', '8', '--version', '21')
    assert revised.returncode == 0, revised.stderr
    settings = properties(revised.stdout)
    assert settings['generator.selection.count'] == '2'
    assert settings['generator.selection.0.version'] == '17.0.3.1'
    assert settings['generator.selection.1.version'] == '21'
    assert run('--input', str(output), '--output', str(output), '--force').returncode != 0
    bad = Path(directory) / 'bad.properties'
    bad.write_text(revised.stdout.replace('javaVersion=17.0.3.1', 'javaVersion=17.0.99'))
    assert run('--input', str(bad)).returncode != 0
    assert run('--input', str(root / 'example-generator-versions.properties')).returncode == 0
    assert run('--catalog', str(resources / 'catalog.json'), '--all').stdout == all_versions.stdout
    assert run('--family', '8', '--exclude-family', '8').returncode != 0
    assert run('--version', 'unknown').returncode != 0
    assert run('--family', '999').returncode != 0
print('Passed packaged CLI, all catalog mappings, bundled host and OS-version catalogs, includes/excludes, saved selection reload, legacy configuration compatibility, invalid data, and overwrite protection.')
