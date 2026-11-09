"""CI signing regression checks use synthetic material, never an existing signing key."""

import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import unittest


HELPER = Path(__file__).resolve().parents[1] / "signing.py"
SECRETS = {
    "KEYSTORE": base64.b64encode(b"synthetic-test-keystore-only").decode(),
    "KEYSTORE_PASSWORD": " store pass\\with:equals=and apostrophe'\nnext line \u2603",
    "KEY_ALIAS": "test alias:with=separators",
    "KEY_PASSWORD": " key pass\\line\twith\rcontrols \U0001F331",
}


class SigningHelperTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="signing-helper-test-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.runner = self.root / "runner"
        self.workspace = self.root / "workspace"
        self.runner.mkdir()
        self.workspace.mkdir()
        self.github_env = self.root / "github-env"
        self.github_output = self.root / "github-output"
        self.env = dict(os.environ)
        for name in (*SECRETS, "SIGNING_STATE", "LAWNCHAIR_KEYSTORE_PROPERTIES", "LAWNCHAIR_KEYSTORE_PASSWORD", "LAWNCHAIR_KEY_PASSWORD"):
            self.env.pop(name, None)
        self.env.update(SECRETS)
        self.env.update({
            "RUNNER_TEMP": str(self.runner),
            "GITHUB_ENV": str(self.github_env),
            "GITHUB_OUTPUT": str(self.github_output),
        })

    def run_helper(self, action, env=None):
        return subprocess.run(
            [os.sys.executable, str(HELPER), action],
            cwd=self.workspace,
            env=env or self.env,
            capture_output=True,
            text=True,
            encoding="utf-8",
        )

    def prepared_paths(self):
        result = self.run_helper("prepare")
        self.assertEqual(result.returncode, 0, result.stderr)
        for value in SECRETS.values():
            self.assertNotIn(value, result.stdout + result.stderr)
        state = Path(self.github_output.read_text(encoding="utf-8").split("=", 1)[1].strip())
        properties = Path(self.github_env.read_text(encoding="utf-8").split("=", 1)[1].strip())
        self.assertEqual(state.parent.parent, self.runner)
        self.assertTrue(state.parent.name.startswith("lawnchair-signing-"))
        self.assertEqual(properties.parent, state.parent)
        return state, properties

    def test_prepare_keeps_material_private_and_exports_only_paths(self):
        state, properties = self.prepared_paths()
        self.assertEqual((state.parent / "key.jks").read_bytes(), b"synthetic-test-keystore-only")
        self.assertFalse((self.workspace / "keystore.properties").exists())
        for value in SECRETS.values():
            self.assertNotIn(value, self.github_env.read_text(encoding="utf-8"))
            self.assertNotIn(value, self.github_output.read_text(encoding="utf-8"))
        if os.name == "posix":
            self.assertEqual(stat.S_IMODE(state.parent.stat().st_mode), 0o700)
            for path in (state, properties, state.parent / "key.jks"):
                self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)

    def test_cleanup_removes_its_files_and_is_repeatable(self):
        state, _ = self.prepared_paths()
        env = {**self.env, "SIGNING_STATE": str(state)}
        result = self.run_helper("cleanup", env)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(state.parent.exists())
        self.assertTrue(self.github_env.read_text(encoding="utf-8").endswith("LAWNCHAIR_KEYSTORE_PROPERTIES=\n"))
        self.assertEqual(self.run_helper("cleanup", env).returncode, 0)

    def test_workspace_configuration_is_never_overwritten_or_removed(self):
        existing = self.workspace / "keystore.properties"
        existing.write_text("existing-user-configuration", encoding="utf-8")
        state, _ = self.prepared_paths()
        self.assertEqual(self.run_helper("cleanup", {**self.env, "SIGNING_STATE": str(state)}).returncode, 0)
        self.assertEqual(existing.read_text(encoding="utf-8"), "existing-user-configuration")

    def test_each_missing_secret_fails_without_material(self):
        for name in SECRETS:
            with self.subTest(name=name):
                env = {**self.env, name: ""}
                result = self.run_helper("prepare", env)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(name, result.stderr)
                self.assertEqual(list(self.runner.iterdir()), [])

    def test_invalid_base64_fails_without_material_or_secret_echo(self):
        value = "not-base64-sensitive-sentinel@"
        result = self.run_helper("prepare", {**self.env, "KEYSTORE": value})
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn(value, result.stdout + result.stderr)
        self.assertEqual(list(self.runner.iterdir()), [])

    def test_failed_export_cleans_prepared_key_and_properties(self):
        result = self.run_helper("prepare", {**self.env, "GITHUB_ENV": str(self.workspace)})
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(list(self.runner.iterdir()), [])

    def test_failed_cleanup_output_export_leaves_no_signing_files(self):
        result = self.run_helper("prepare", {**self.env, "GITHUB_OUTPUT": str(self.workspace)})
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(list(self.runner.iterdir()), [])

    def make_symlink(self, target, link, directory=False):
        try:
            link.symlink_to(target, target_is_directory=directory)
        except (OSError, NotImplementedError):
            self.skipTest("This host does not allow creating symbolic links")

    def test_cleanup_rejects_symlink_directory(self):
        outside = self.root / "outside"
        outside.mkdir()
        state = outside / "state.json"
        state.write_text(json.dumps({"schema": 1, "created_by": "lawnchair-ci-signing"}), encoding="utf-8")
        key = outside / "key.jks"
        key.write_bytes(b"must-remain")
        link = self.runner / "lawnchair-signing-link"
        self.make_symlink(outside, link, directory=True)
        result = self.run_helper("cleanup", {**self.env, "SIGNING_STATE": str(link / "state.json")})
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(key.read_bytes(), b"must-remain")

    def test_cleanup_rejects_symlink_metadata(self):
        outside = self.root / "outside-state.json"
        outside.write_text(json.dumps({"schema": 1, "created_by": "lawnchair-ci-signing"}), encoding="utf-8")
        directory = self.runner / "lawnchair-signing-link"
        directory.mkdir()
        state = directory / "state.json"
        self.make_symlink(outside, state)
        key = directory / "key.jks"
        key.write_bytes(b"must-remain")
        result = self.run_helper("cleanup", {**self.env, "SIGNING_STATE": str(state)})
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(key.read_bytes(), b"must-remain")

    def test_cleanup_unlinks_key_symlink_without_deleting_target(self):
        outside = self.root / "outside-key"
        outside.write_bytes(b"must-remain")
        directory = self.runner / "lawnchair-signing-link"
        directory.mkdir()
        state = directory / "state.json"
        state.write_text(json.dumps({"schema": 1, "created_by": "lawnchair-ci-signing"}), encoding="utf-8")
        self.make_symlink(outside, directory / "key.jks")
        result = self.run_helper("cleanup", {**self.env, "SIGNING_STATE": str(state)})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(outside.read_bytes(), b"must-remain")
        self.assertFalse(directory.exists())

    def test_cleanup_rejects_paths_outside_runner_temp(self):
        outside = self.root / "lawnchair-signing-unrelated"
        outside.mkdir()
        state = outside / "state.json"
        state.write_text(json.dumps({"schema": 1, "created_by": "lawnchair-ci-signing"}), encoding="utf-8")
        key = outside / "key.jks"
        key.write_bytes(b"must-remain")
        result = self.run_helper("cleanup", {**self.env, "SIGNING_STATE": str(state)})
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(key.read_bytes(), b"must-remain")

    def test_cleanup_rejects_unknown_metadata(self):
        directory = self.runner / "lawnchair-signing-unrelated"
        directory.mkdir()
        state = directory / "state.json"
        state.write_text("{}", encoding="utf-8")
        key = directory / "key.jks"
        key.write_bytes(b"must-remain")
        result = self.run_helper("cleanup", {**self.env, "SIGNING_STATE": str(state)})
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(key.read_bytes(), b"must-remain")

    def test_cleanup_without_state_is_a_noop(self):
        result = self.run_helper("cleanup")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(list(self.runner.iterdir()), [])

    @unittest.skipUnless(shutil.which("java"), "JDK needed to validate java.util.Properties parsing")
    def test_java_properties_round_trip_preserves_passwords_and_alias(self):
        _, properties = self.prepared_paths()
        source = self.root / "ReadProperties.java"
        source.write_text(
            "import java.io.*; import java.nio.charset.*; import java.security.*; import java.util.*; "
            "class ReadProperties { public static void main(String[] args) throws Exception { "
            "Properties p=new Properties(); try(var input=new FileInputStream(args[0])){p.load(input);} "
            "for(int i=1;i<args.length;i++){System.out.println(HexFormat.of().formatHex("
            "MessageDigest.getInstance(\"SHA-256\").digest(p.getProperty(args[i]).getBytes(StandardCharsets.UTF_8))));} }}",
            encoding="utf-8",
        )
        result = subprocess.run(
            ["java", "--source", "21", str(source), str(properties), "storePassword", "keyAlias", "keyPassword"],
            capture_output=True, text=True, encoding="utf-8", env=self.env,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        expected = [hashlib.sha256(SECRETS[name].encode("utf-8")).hexdigest() for name in ("KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")]
        self.assertEqual(result.stdout.splitlines(), expected)


if __name__ == "__main__":
    unittest.main()
