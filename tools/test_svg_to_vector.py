"""Run with python -B -m unittest discover -s tools -p 'test_*.py'."""

from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
import xml.etree.ElementTree as ET

from svg_to_vector import ANDROID, CIRCLE, convert, path_data


def attr(element, name):
    return element.get(f"{{{ANDROID}}}{name}")


class SvgToVectorTest(unittest.TestCase):
    def render(self, body, flag=False):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "test.svg"
            path.write_text(f'<svg viewBox="0 0 512 512">{body}</svg>', encoding="utf-8")
            first = convert(path, flag)
            self.assertEqual(first, convert(path, flag))
            return ET.fromstring(first)

    def test_path_commands_are_preserved(self):
        data = "M1 2 L3 4 H5 V6 C1 2 3 4 5 6 S1 2 3 4 Q1 2 3 4 T5 6 A2 3 0 0 1 5 6 Z m1 2 l3 4 h5 v6 c1 2 3 4 5 6 s1 2 3 4 q1 2 3 4 t5 6 a2 3 0 0 1 5 6 z"
        vector = self.render(f'<path d="{data}"/>')
        self.assertEqual(attr(vector[0], "pathData"), data)

    def test_inherited_strokes_and_tint(self):
        vector = self.render('<g fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/></g>')
        self.assertEqual(attr(vector, "tint"), "?android:attr/colorControlNormal")
        path = vector[0]
        for key, value in dict(fillColor="#00000000", strokeColor="#FF000000", strokeWidth="1.5", strokeLineCap="round", strokeLineJoin="round").items():
            self.assertEqual(attr(path, key), value)

    def test_mask_clip_and_original_colours(self):
        vector = self.render('<mask id="m"><circle cx="256" cy="256" r="256" fill="#fff"/></mask><g mask="url(#m)"><path fill="#123" d="M0 0h512v512z"/></g>', True)
        self.assertIsNone(attr(vector, "tint"))
        group = vector[0]
        self.assertEqual(group[0].tag, "clip-path")
        self.assertEqual(attr(group[0], "pathData"), CIRCLE)
        self.assertEqual(attr(group[1], "fillColor"), "#FF112233")

    def test_shapes(self):
        for svg in ('<rect x="1" y="2" width="8" height="6"/>',
                    '<rect width="8" height="6" rx="2"/>',
                    '<circle cx="5" cy="6" r="2"/>',
                    '<ellipse cx="5" cy="6" rx="2" ry="3"/>',
                    '<line x1="1" y1="2" x2="3" y2="4"/>',
                    '<polygon points="0,0 1,1 2,0"/>'):
            with self.subTest(svg=svg):
                data = path_data(ET.fromstring(svg))
                self.assertTrue(data.startswith("M"))
                self.assertNotIn("nan", data)
        self.assertIn("A2.0,2.0", path_data(ET.fromstring('<rect width="8" height="6" rx="2"/>')))

    def test_reference_and_translation(self):
        vector = self.render('<defs><path id="p" fill="#eee" d="M0 0h5v5z"/></defs><g transform="translate(.2)"><use href="#p"/></g>', True)
        self.assertEqual(len(vector), 1)
        self.assertEqual(attr(vector[0], "translateX"), ".2")
        self.assertEqual(attr(vector[0][0], "fillColor"), "#FFeeeeee")

    def test_unsupported_features_fail(self):
        for body in ('<path d="M0 0" transform="skewX(2)"/>',
                     '<path d="M0 0" stroke-dasharray="2 2"/>',
                     '<text>unsupported</text>'):
            with self.subTest(body=body), self.assertRaises(ValueError):
                self.render(body)


if __name__ == "__main__":
    unittest.main()
