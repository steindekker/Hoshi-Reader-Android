package screenshot

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"os"
	"time"

	// Decoders for whatever capture tools produce.
	_ "image/gif"
	_ "image/png"

	_ "golang.org/x/image/bmp"
	"golang.org/x/image/draw"
	_ "golang.org/x/image/webp"
)

// EncodeJPEG scales img down to at most maxWidth pixels wide (0 = keep size,
// never upscale) and encodes it as an opaque JPEG.
func EncodeJPEG(img image.Image, maxWidth, quality int) ([]byte, error) {
	if quality < 1 || quality > 100 {
		return nil, fmt.Errorf("quality %d out of range 1-100", quality)
	}
	dst, err := Flatten(img, maxWidth)
	if err != nil {
		return nil, err
	}
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, dst, &jpeg.Options{Quality: quality}); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

// Flatten returns an opaque RGBA copy of img composited onto black (so
// transparent pixels do not turn into garbage), scaled down to maxWidth
// when it is wider (0 = keep size).
func Flatten(img image.Image, maxWidth int) (*image.RGBA, error) {
	b := img.Bounds()
	w, h := b.Dx(), b.Dy()
	if w <= 0 || h <= 0 {
		return nil, errors.New("empty image")
	}
	dw, dh := w, h
	if maxWidth > 0 && w > maxWidth {
		dw = maxWidth
		dh = max(int(float64(h)*float64(maxWidth)/float64(w)+0.5), 1)
	}
	dst := image.NewRGBA(image.Rect(0, 0, dw, dh))
	draw.Draw(dst, dst.Bounds(), image.NewUniform(color.Black), image.Point{}, draw.Src)
	if dw == w && dh == h {
		draw.Draw(dst, dst.Bounds(), img, b.Min, draw.Over)
	} else {
		draw.CatmullRom.Scale(dst, dst.Bounds(), img, b, draw.Over, nil)
	}
	return dst, nil
}

// DecodeFile decodes an image file.
func DecodeFile(path string) (image.Image, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	img, _, err := image.Decode(f)
	return img, err
}

// waitForImage polls until path exists, was modified at or after notBefore,
// and decodes successfully (a half-written file fails to decode).
func waitForImage(ctx context.Context, path string, notBefore time.Time) (image.Image, error) {
	var lastErr error
	for {
		st, err := os.Stat(path)
		switch {
		case err != nil:
			lastErr = err
		case st.ModTime().Before(notBefore):
			lastErr = fmt.Errorf("%s was not updated", path)
		case st.Size() == 0:
			lastErr = fmt.Errorf("%s is empty", path)
		default:
			img, derr := DecodeFile(path)
			if derr == nil {
				return img, nil
			}
			lastErr = derr
		}
		select {
		case <-ctx.Done():
			return nil, fmt.Errorf("waiting for %s: %w (last: %v)", path, ctx.Err(), lastErr)
		case <-time.After(50 * time.Millisecond):
		}
	}
}
