import unittest

from eval_wer import cer, edit_distance, normalize, wer


class EvalMath(unittest.TestCase):
    def test_edit_distance(self):
        self.assertEqual(edit_distance([], []), 0)
        self.assertEqual(edit_distance(list("kitten"), list("sitting")), 3)
        self.assertEqual(edit_distance(["a"], []), 1)

    def test_normalisation_keeps_devanagari_and_drops_punctuation(self):
        self.assertEqual(normalize("नमस्ते, मलाई ज्वरो छ।"), "नमस्ते मलाई ज्वरो छ")
        self.assertEqual(normalize("  BP १५० by 95!  "), "bp 150 by 95")
        self.assertEqual(normalize("café"), "café")

    def test_perfect_and_empty(self):
        self.assertEqual(wer("तीन दिनदेखि ज्वरो", "तीन दिनदेखि ज्वरो।"), (0, 3))
        self.assertEqual(wer("तीन दिन", ""), (2, 2))

    def test_wer_counts_substitution_insertion_deletion(self):
        self.assertEqual(wer("a b c d", "a x c d"), (1, 4))   # substitution
        self.assertEqual(wer("a b c d", "a b c d e"), (1, 4))  # insertion
        self.assertEqual(wer("a b c d", "a c d"), (1, 4))      # deletion

    def test_cer_ignores_spaces(self):
        self.assertEqual(cer("ज्वरो छ", "ज्वरोछ"), (0, len(normalize("ज्वरोछ"))))
        e, n = cer("abcd", "abxd")
        self.assertEqual((e, n), (1, 4))

    def test_digits_compare_equal_across_scripts(self):
        self.assertEqual(wer("तापक्रम १०१", "तापक्रम 101"), (0, 2))


if __name__ == "__main__":
    unittest.main()
