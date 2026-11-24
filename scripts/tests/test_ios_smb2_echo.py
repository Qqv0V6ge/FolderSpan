"""无需服务器或 Apple SDK，编译并验证 iOS SMB 心跳适配器的传输边界。"""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class IosSmb2EchoTest(unittest.TestCase):
    def test_transport_failures_and_callback_lifetimes(self):
        compiler = shutil.which("cc")
        if compiler is None:
            self.skipTest("未安装 C 编译器")
        root = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory(prefix="folderspan-smb2-test-") as temporary:
            directory = Path(temporary)
            include = directory / "smb2"
            include.mkdir()
            # 只替换外部库的公开接口；被测包装头始终来自项目实际代码。
            (include / "smb2.h").write_text("", encoding="utf-8")
            (include / "libsmb2.h").write_text(
                "struct smb2_context;\n"
                "typedef void (*smb2_command_cb)(struct smb2_context *, int, void *, void *);\n"
                "int smb2_get_fd(struct smb2_context *);\n"
                "int smb2_which_events(struct smb2_context *);\n"
                "int smb2_echo_async(struct smb2_context *, smb2_command_cb, void *);\n"
                "int smb2_service(struct smb2_context *, int);\n",
                encoding="utf-8",
            )
            executable = directory / "echo-test"
            compilation = subprocess.run(
                [compiler, "-std=c11", "-Wall", "-Wextra", "-Werror",
                 "-fsanitize=address,undefined", "-fno-omit-frame-pointer",
                 f"-I{directory}", f"-I{root / 'core/src/iosMain/cinterop'}",
                 str(root / "scripts/tests/ios_smb2_echo_test.c"), "-o", str(executable)],
                capture_output=True, text=True,
            )
            self.assertEqual(compilation.returncode, 0, compilation.stderr)
            execution = subprocess.run([str(executable)], capture_output=True, text=True)
            self.assertEqual(execution.returncode, 0, execution.stdout + execution.stderr)


if __name__ == "__main__":
    unittest.main()
