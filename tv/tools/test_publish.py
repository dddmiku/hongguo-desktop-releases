import copy
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/"tools"))
import publish

class PublishTest(unittest.TestCase):
    def test_feed_keeps_pc_version_and_migration(self):
        previous={"version":"1.9.12","sha256":"PC-HASH","notes":"PC notes","migration":{"targetVersion":"1.2.0"},"platforms":{"windows-x86_64":{"version":"1.9.12","sha256":"PC-HASH","url":"https://github.com/example/PC.exe"},"android-tv":{"version":"1.0.9"}}}
        original=copy.deepcopy(previous)
        with tempfile.TemporaryDirectory(dir=ROOT/"_work") as name:
            apk=Path(name)/"hongguo-tv-1.1.0.APK";apk.write_bytes(b"signed-test-placeholder")
            result=publish.feed(previous,"1.1.0",11,apk,"TV notes")
        self.assertEqual(result["version"],previous["version"]);self.assertEqual(result["sha256"],previous["sha256"]);self.assertEqual(result["platforms"]["windows-x86_64"],previous["platforms"]["windows-x86_64"]);self.assertEqual(result["migration"],previous["migration"]);self.assertEqual(result["platforms"]["android-tv"]["versionCode"],11);self.assertEqual(previous,original)
    def test_public_assets_are_immutable(self):
        with self.assertRaisesRegex(RuntimeError,"已公开"):publish.require_draft({"draft":False})
        publish.require_draft({"draft":True})
    def test_missing_pc_feed_blocks_publication(self):
        with self.assertRaisesRegex(RuntimeError,"PC"):publish.feed({},"1.1.0",11,Path("unused.APK"),"")

if __name__=="__main__":unittest.main()
