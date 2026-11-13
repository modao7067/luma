#!/usr/bin/env python3
from pathlib import Path
import os
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / 'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.pi import PiSession

class SkillTest(unittest.TestCase):
    def test_pi_discovers_both_builtin_skills_from_the_same_environment(self):
        class StopFixture(Exception): pass
        for root in ('/opt/luma/agent/skills', '/proc/1/root/data/user/0/fixture/files/runtime/agent/root/skills'):
            session = PiSession({'cli': 'pi', 'cwd': '/work', 'browserConfig': '/private/binding'}, lambda: None)
            argv = []
            def start(args):
                argv.extend(args)
                raise StopFixture()
            session.start = start
            with patch.dict(os.environ, {'LUMA_BROWSER_SKILL': root + '/luma-browser/SKILL.md'}):
                with self.assertRaises(StopFixture): session.connect()
            skills = [argv[index + 1] for index, value in enumerate(argv[:-1]) if value == '--skill']
            self.assertEqual([root + '/luma-browser/SKILL.md', root + '/luma-sessions/SKILL.md'], skills)

if __name__ == '__main__': unittest.main()
