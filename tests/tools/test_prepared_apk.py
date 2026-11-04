import pathlib
import subprocess
import sys
import tempfile
import unittest
import zipfile
import hashlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'tools/verify_prepared_apk.py'


class PreparedApkTest(unittest.TestCase):
    def test_only_signatures_may_differ(self):
        with tempfile.TemporaryDirectory() as folder:
            def apk(name, values):
                path = pathlib.Path(folder) / name
                with zipfile.ZipFile(path, 'w') as out:
                    for key, value in values.items():
                        out.writestr(key, value)
                return path

            common = {'AndroidManifest.xml': b'manifest', 'classes.dex': b'dex', 'resources.arsc': b'resources', 'META-INF/app.kotlin_module': b'kotlin'}
            built = apk('built.apk', common | {'META-INF/DEBUG.SF': b'old', 'META-INF/DEBUG.RSA': b'old'})
            prepared = apk('prepared.apk', common | {'META-INF/RELEASE.SF': b'new', 'META-INF/RELEASE.RSA': b'new'})

            def verify(path, checksum=None):
                digest = checksum or hashlib.sha256(path.read_bytes()).hexdigest()
                return subprocess.run([sys.executable, str(SCRIPT), str(built), str(path), digest], capture_output=True).returncode

            self.assertEqual(0, verify(prepared))
            self.assertNotEqual(0, verify(prepared, '0' * 64))
            self.assertNotEqual(0, verify(apk('changed.apk', common | {'classes.dex': b'another program'})))
            self.assertNotEqual(0, verify(apk('meta.apk', common | {'META-INF/app.kotlin_module': b'changed'})))
            self.assertNotEqual(0, verify(apk('extra.apk', common | {'extra.dex': b'unexpected'})))


if __name__ == '__main__':
    unittest.main()
