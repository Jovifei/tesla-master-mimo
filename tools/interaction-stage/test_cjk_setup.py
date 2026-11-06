"""Exercises the real bounded shell script with isolated package/font commands."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('ensure-cjk-fonts.sh')

class CjkSetupTests(unittest.TestCase):
    def run_case(self, case, hosted=True):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            commands = {
                'timeout': '#!/usr/bin/env bash\nprintf "timeout %s %s %s\\n" "$1" "$2" "$3" >> "$TEST_ROOT/commands"\nshift 3\nexec "$@"\n',
                'sudo': '#!/usr/bin/env bash\nexec "$@"\n',
                'fc-match': '#!/usr/bin/env bash\nif [[ -e "$TEST_ROOT/font" ]]; then echo "Noto Sans CJK SC"; else echo "DejaVu Sans"; fi\n',
                'apt-get': '''#!/usr/bin/env bash
printf 'apt %s\n' "$*" >> "$TEST_ROOT/commands"
if [[ " $* " == *' update '* ]]; then
  [[ "$TEST_CASE" != update-fails ]]
  exit $?
fi
n=0
[[ ! -e "$TEST_ROOT/attempts" ]] || n=$(cat "$TEST_ROOT/attempts")
n=$((n+1)); echo "$n" > "$TEST_ROOT/attempts"
if [[ "$TEST_CASE" == always-fails ]]; then exit 124; fi
if [[ "$TEST_CASE" == first-fails || "$TEST_CASE" == update-fails ]] && [[ "$n" == 1 ]]; then exit 124; fi
if [[ "$TEST_CASE" != missing-font ]]; then touch "$TEST_ROOT/font"; fi
''',
            }
            for name, content in commands.items():
                path=root/name; path.write_text(content); path.chmod(0o700)
            if case == 'already-present': (root/'font').touch()
            env={**os.environ, 'PATH':str(root)+os.pathsep+os.environ['PATH'],
                 'TEST_ROOT':str(root), 'TEST_CASE':case, 'GITHUB_ACTIONS':'true',
                 'RUNNER_ENVIRONMENT':'github-hosted' if hosted else 'self-hosted'}
            result=subprocess.run(['bash', str(SCRIPT)], env=env, text=True, capture_output=True, timeout=5)
            log=(root/'commands').read_text() if (root/'commands').exists() else ''
            return result, log
    def test_existing_font_needs_no_package_or_index_request(self):
        result,log=self.run_case('already-present')
        self.assertEqual(result.returncode,0,result.stderr);self.assertNotIn('apt ',log)
    def test_first_install_does_not_refresh_unrelated_feeds(self):
        result,log=self.run_case('ok')
        self.assertEqual(result.returncode,0,result.stderr);self.assertEqual(log.count('apt '),1)
        self.assertIn('150s',log);self.assertNotIn(' update ',log)
    def test_first_failure_gets_exactly_one_bounded_refresh_and_retry(self):
        result,log=self.run_case('first-fails')
        self.assertEqual(result.returncode,0,result.stderr);self.assertEqual(log.count('apt '),3)
        self.assertEqual(log.count('150s'),2);self.assertEqual(log.count('90s'),1)
    def test_update_failure_and_repeat_timeout_fail_without_looping(self):
        for case,count in [('update-fails',2),('always-fails',3)]:
            with self.subTest(case=case):
                result,log=self.run_case(case)
                self.assertNotEqual(result.returncode,0);self.assertEqual(log.count('apt '),count)
    def test_success_without_renderable_font_is_not_a_pass(self):
        result,log=self.run_case('missing-font')
        self.assertNotEqual(result.returncode,0);self.assertIn('required font family',result.stderr)
    def test_owner_or_self_hosted_machine_is_rejected_before_package_actions(self):
        result,log=self.run_case('ok',hosted=False)
        self.assertEqual(result.returncode,2);self.assertEqual(log,'')

if __name__=='__main__': unittest.main()
