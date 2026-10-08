"""Les mesures doivent rejeter une image sèche et un trou de cadence."""
import unittest
from e2e.analyser_video import measures


class VideoAnalysisTest(unittest.TestCase):
    def smooth(self):
        samples = []
        for i in range(20):
            progress = min(1, max(0, (i - 2) / 12))
            ratios = [1 - .25 * progress, 1 - .28 * progress, 1 - .35 * progress]
            samples.append({'pts': i / 60, 'veil': sum(ratios) / 3, 'ratios': ratios,
                'pill': 1 - .8 * progress, 'top': 200 - 28 * progress})
        return samples

    def test_smooth_has_intermediate_images_and_measured_duration(self):
        report = measures(self.smooth(), 'entree')
        self.assertTrue(report['success'], report['checks'])
        self.assertEqual(11, report['intermediate_frames'])
        self.assertAlmostEqual(183.33, report['duration_ms'], places=1)

    def test_dry_entrance_fails_without_relaxing_thresholds(self):
        samples = self.smooth()
        report = measures([samples[0], samples[-1]], 'entree')
        self.assertFalse(report['success'])
        self.assertFalse(report['checks']['intermediate_frames'])
        self.assertFalse(report['checks']['first_pill'])
        self.assertFalse(report['checks']['max_jump'])

    def test_timestamp_gap_is_detected_instead_of_resampled(self):
        samples = self.smooth()
        for sample in samples[8:]:
            sample['pts'] += .15
        report = measures(samples, 'entree')
        self.assertFalse(report['checks']['max_pts_gap'])
        self.assertGreater(report['max_pts_gap_ms'], 100)


if __name__ == '__main__':
    unittest.main()
