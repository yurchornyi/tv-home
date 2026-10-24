"""The tv-launcher-tmdb Worker (tools/tmdb-proxy) and what SENTRA.md forbids.

The Worker's own tests (accounts kept apart, the PIN only as a hash, wrong PINs locking, shelves that come back empty
instead of a 500) run under Node (tools/tmdb-proxy/test) and skip without it. The rest reads the sources: no AI
provider is called and no provider key is kept anywhere, the AI goes only through Sentra with a role, never a model,
the APK carries no TMDB key, and ADMIN_EMAIL is a secret, not a var.

Run from the repository root: python3 -m unittest discover -s tests -v
"""
from pathlib import Path
import re
import shutil
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]
PROXY = ROOT / "tools" / "tmdb-proxy"
NODE = shutil.which("node")
PROVIDERS = re.compile(
    r"api\.anthropic\.com|@anthropic-ai|anthropic-version|x-api-key|api\.openai\.com|generativelanguage\.googleapis"
    r"|api\.groq\.com|openrouter\.ai|sk-ant-|ANTHROPIC_API_KEY|OPENAI_API_KEY|GEMINI_API_KEY|GROQ_API_KEY",
    re.IGNORECASE,
)


def tracked(*paths):
    out = subprocess.run(["git", "ls-files", "--", *paths], cwd=ROOT, capture_output=True, text=True, check=True)
    return [ROOT / p for p in out.stdout.splitlines() if (ROOT / p).suffix in {".js", ".mjs", ".java", ".xml",
                                                                             ".gradle", ".toml", ".json", ".sh"}]


class WorkerTests(unittest.TestCase):
    @unittest.skipUnless(NODE, "node is not on PATH")
    def test_node_suite(self):
        tests = sorted(str(p.relative_to(PROXY)) for p in (PROXY / "test").glob("*.test.mjs"))
        self.assertTrue(tests)
        r = subprocess.run([NODE, "--import", "./test/register.mjs", "--test", *tests], cwd=PROXY,
                           capture_output=True, text=True, timeout=120)
        self.assertEqual(r.returncode, 0, r.stdout[-4000:] + r.stderr[-2000:])


class SentraRules(unittest.TestCase):
    def test_no_ai_provider_called_or_keyed(self):
        for path in tracked("launcher", "tools/tmdb-proxy"):
            text = path.read_text(errors="ignore")
            self.assertIsNone(PROVIDERS.search(text), f"{path.relative_to(ROOT)} names an AI provider directly")

    def test_ai_only_through_sentra_by_role(self):
        ai = (PROXY / "ai.js").read_text()
        self.assertEqual(re.findall(r"fetch\(env\.(\w+)", ai), ["SENTRA_URL"])
        self.assertIn("model: 'role:' + role", ai)
        self.assertEqual(set(re.findall(r"skill: '[\w-]+', role: '(\w+)'", ai)), {"fast", "quality"})
        self.assertEqual(set(re.findall(r"skill: '([\w-]+)'", ai)), {"tv-command", "tv-picks", "tv-about"})
        # Sentra caches picks and about: the Worker keeps no AI cache of its own.
        self.assertNotIn("caches.", ai)

    def test_apk_carries_no_tmdb_key(self):
        gradle = (ROOT / "launcher" / "app" / "build.gradle").read_text()
        self.assertNotIn("TMDB_KEY", gradle)
        for path in tracked("launcher/app/src"):
            text = path.read_text(errors="ignore")
            self.assertNotIn("api.themoviedb.org", text, path)
            self.assertNotIn("TMDB_KEY", text, path)

    def test_admin_email_is_a_secret(self):
        toml = (PROXY / "wrangler.toml").read_text()
        values = toml.split("[vars]", 1)[1] if "[vars]" in toml else ""
        self.assertIsNone(re.search(r"^\s*ADMIN_EMAIL\s*=", values, re.MULTILINE))
        self.assertNotRegex(toml, r"@[a-z0-9-]+\.[a-z]{2,}")


if __name__ == "__main__":
    unittest.main()
