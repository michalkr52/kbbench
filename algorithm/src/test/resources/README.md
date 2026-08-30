# Test images for `algorithm`

## `input.png` — the single-frame smoke image

- Put your own input image here as `input.png`; the image-IO tests auto-generate
  `generated_input.png` when it is missing.
- Outputs and metrics reports go to `build/test-output/` (relative to the `algorithm` module
  directory). Metrics cover before/after min/max/range/mean/std plus luma stats.

| Test | Images | Report |
| --- | --- | --- |
| `ContrastStretchingImageIoTest` | `contrast_output.png` | `contrast_metrics.txt` |
| `GuidedFilterImageIoTest` | `guided_output.png`, `guided_color_output.png` | `guided_metrics.txt` |
| `BilateralImageIoTest` | `bilateral_output.png` | `bilateral_metrics.txt` |
| `LinearUnsharpMaskImageIoTest` | `linear_um_output.png` | `linear_um_metrics.txt` |
| `AdaptiveUnsharpMaskImageIoTest` | `aum_output.png` | `aum_metrics.txt` |

Note that the PSNR/SSIM in those reports are measured against the *input*. They say how far a
filter moved the image, not how good the result is. The two directories below exist to give the
tests real ground truth, where higher scores genuinely mean better.

## `clean/` — sharp references for degrade-and-restore

Drop clean, low-noise images here (Set14, Kodak24, CBSD68, DIV2K validation — anything sharp).
`SharpeningRestorationTest` blurs each one, optionally adds noise, asks each sharpener to undo the
damage, and scores the result against the untouched original. Reports to
`build/test-output/sharpening_restoration.txt`.

Without this directory the test falls back to `input.png`, which still works but is a weaker truth:
it is a real photo and carries sensor noise, so the noise-free rows partly reward an algorithm for
reproducing that noise rather than for restoring detail.

## `pairs/` — real noisy/clean captures

```
pairs/clean/<name>.png    long-exposure or averaged ground truth
pairs/noisy/<name>.png    the real noisy capture of the same scene, same filename
```

Filenames must match across the two directories; a clean frame without a partner fails the load
rather than being skipped. `DenoiseGroundTruthTest` scores every denoiser against the clean capture
and reports to `build/test-output/denoise_ground_truth.txt`. The test is **skipped** when `pairs/`
is empty, so it never passes vacuously.

Sources worth using, all of which need renaming into the layout above:

- **SIDD** (smartphone captures — closest to what this app benchmarks); the validation or Small
  subset is enough. SIDD names files `..._GT_SRGB_...` / `..._NOISY_SRGB_...`.
- **PolyU** real-world noisy images.
- **RENOIR** low-light pairs.

`pairs/` is gitignored: these datasets are licensed for research use and forbid redistribution, so
each checkout supplies its own copy. Frames are centre-cropped to 512 px and never downscaled —
resampling averages noise away and would flatter every denoiser.
