import unittest

from protocol import key_sequence, normalized_point

class ProtocolTests(unittest.TestCase):
    def test_hotkeys(self):
        self.assertEqual(key_sequence("CTRL+V"), ("ctrl", "v"))
        self.assertEqual(key_sequence("ESC"), ("esc",))
    def test_reject_unknown(self):
        with self.assertRaises(ValueError):
            key_sequence("WIN+R")
    def test_point(self):
        self.assertEqual(normalized_point(0, 0, -1920, 0, 1920, 1080), (-1920, 0))
        self.assertEqual(normalized_point(1, 1, -1920, 0, 1920, 1080), (-1, 1079))
    def test_invalid(self):
        for p in ((-0.1, 0), (1.1, 0.5), (True, 0), ("x", 1)):
            with self.assertRaises(ValueError):
                normalized_point(*p, 0, 0, 1920, 1080)

if __name__ == "__main__":
    unittest.main()
