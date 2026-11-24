import signal
import subprocess
import unittest
from unittest.mock import MagicMock, patch

import run


class RunnerTests(unittest.TestCase):
    def test_one_service_exiting_stops_the_other_service(self):
        active = MagicMock(pid=12345)
        active.poll.return_value = None
        failed = MagicMock(pid=12346)
        failed.poll.return_value = 2
        with patch.object(run.subprocess, "Popen", side_effect=[active, failed]), \
                patch.object(run.os, "killpg") as kill_group:
            self.assertEqual(run.supervise([["ftp"], ["rsbuild"]], {}), 2)
            kill_group.assert_called_once_with(12345, signal.SIGTERM)
            active.wait.assert_called_once_with(timeout=5)

    def test_failed_second_start_still_cleans_up_the_first_service(self):
        active = MagicMock(pid=12345)
        active.poll.return_value = None
        with patch.object(run.subprocess, "Popen", side_effect=[active, FileNotFoundError("missing")]), \
                patch.object(run.os, "killpg") as kill_group:
            with self.assertRaises(FileNotFoundError):
                run.supervise([["ftp"], ["rsbuild"]], {})
            kill_group.assert_called_once_with(12345, signal.SIGTERM)

    def test_runner_propagates_failed_build_status(self):
        with patch.object(run, "execute", side_effect=subprocess.CalledProcessError(7, ["npm"])):
            self.assertEqual(run.main(["build"]), 7)


if __name__ == "__main__":
    unittest.main()
