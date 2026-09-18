package main

/*
#cgo pkg-config: vpx
#include <vpx/vpx_decoder.h>
#include <vpx/vp8dx.h>
#include <vpx/vpx_encoder.h>
#include <vpx/vp8cx.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
    vpx_codec_ctx_t ctx;
} NativeVP8Decoder;

static NativeVP8Decoder* create_vp8_decoder() {
    NativeVP8Decoder *dec = (NativeVP8Decoder*)calloc(1, sizeof(NativeVP8Decoder));
    if (!dec) return NULL;
    vpx_codec_dec_cfg_t cfg;
    memset(&cfg, 0, sizeof(cfg));
    cfg.threads = 2;
    if (vpx_codec_dec_init(&dec->ctx, vpx_codec_vp8_dx(), &cfg, 0) != VPX_CODEC_OK) {
        free(dec);
        return NULL;
    }
    return dec;
}

static void destroy_vp8_decoder(NativeVP8Decoder *dec) {
    if (dec) {
        vpx_codec_destroy(&dec->ctx);
        free(dec);
    }
}

static int decode_vp8_frame(
    NativeVP8Decoder *dec,
    const unsigned char *in_data,
    unsigned int in_len,
    int *out_w,
    int *out_h,
    const unsigned char **out_y,
    const unsigned char **out_u,
    const unsigned char **out_v,
    int *stride_y,
    int *stride_u,
    int *stride_v
) {
    if (!dec || !in_data || in_len == 0) return 0;
    if (vpx_codec_decode(&dec->ctx, in_data, in_len, NULL, 0) != VPX_CODEC_OK) {
        return 0;
    }
    vpx_codec_iter_t iter = NULL;
    vpx_image_t *img = vpx_codec_get_frame(&dec->ctx, &iter);
    if (!img) return 0;

    *out_w = img->d_w;
    *out_h = img->d_h;
    *out_y = img->planes[VPX_PLANE_Y];
    *out_u = img->planes[VPX_PLANE_U];
    *out_v = img->planes[VPX_PLANE_V];
    *stride_y = img->stride[VPX_PLANE_Y];
    *stride_u = img->stride[VPX_PLANE_U];
    *stride_v = img->stride[VPX_PLANE_V];
    return 1;
}

typedef struct {
    vpx_codec_ctx_t ctx;
    vpx_image_t raw;
    int width;
    int height;
    int pts;
} NativeVP8Encoder;

static NativeVP8Encoder* create_vp8_encoder(int width, int height, int fps, int bitrate_kbps) {
    NativeVP8Encoder *enc = (NativeVP8Encoder*)calloc(1, sizeof(NativeVP8Encoder));
    if (!enc) return NULL;

    vpx_codec_enc_cfg_t cfg;
    if (vpx_codec_enc_config_default(vpx_codec_vp8_cx(), &cfg, 0) != VPX_CODEC_OK) {
        free(enc);
        return NULL;
    }

    cfg.g_w = width;
    cfg.g_h = height;
    cfg.g_timebase.num = 1;
    cfg.g_timebase.den = fps > 0 ? fps : 30;
    cfg.rc_target_bitrate = bitrate_kbps > 0 ? bitrate_kbps : 1500;
    cfg.g_error_resilient = VPX_ERROR_RESILIENT_DEFAULT;
    cfg.g_threads = 2;

    if (vpx_codec_enc_init(&enc->ctx, vpx_codec_vp8_cx(), &cfg, 0) != VPX_CODEC_OK) {
        free(enc);
        return NULL;
    }

    vpx_codec_control(&enc->ctx, VP8E_SET_CPUUSED, 8);
    vpx_codec_control(&enc->ctx, VP8E_SET_STATIC_THRESHOLD, 100);
    vpx_codec_control(&enc->ctx, VP8E_SET_TOKEN_PARTITIONS, VP8_ONE_TOKENPARTITION);
    vpx_codec_control(&enc->ctx, VP8E_SET_NOISE_SENSITIVITY, 0);

    if (!vpx_img_alloc(&enc->raw, VPX_IMG_FMT_I420, width, height, 1)) {
        vpx_codec_destroy(&enc->ctx);
        free(enc);
        return NULL;
    }

    enc->width = width;
    enc->height = height;
    enc->pts = 0;
    return enc;
}

static void destroy_vp8_encoder(NativeVP8Encoder *enc) {
    if (enc) {
        vpx_img_free(&enc->raw);
        vpx_codec_destroy(&enc->ctx);
        free(enc);
    }
}

static void rgba_to_yuv420(const unsigned char *rgba, vpx_image_t *img, int width, int height) {
    unsigned char *y = img->planes[VPX_PLANE_Y];
    unsigned char *u = img->planes[VPX_PLANE_U];
    unsigned char *v = img->planes[VPX_PLANE_V];
    int y_stride = img->stride[VPX_PLANE_Y];
    int u_stride = img->stride[VPX_PLANE_U];
    int v_stride = img->stride[VPX_PLANE_V];

    for (int j = 0; j < height; j++) {
        for (int i = 0; i < width; i++) {
            int rgba_idx = (j * width + i) * 4;
            int r = rgba[rgba_idx];
            int g = rgba[rgba_idx + 1];
            int b = rgba[rgba_idx + 2];

            int y_val = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
            y[j * y_stride + i] = (unsigned char)(y_val < 0 ? 0 : (y_val > 255 ? 255 : y_val));

            if ((j % 2 == 0) && (i % 2 == 0)) {
                int u_val = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                int v_val = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                int uv_idx = (j / 2) * u_stride + (i / 2);
                u[uv_idx] = (unsigned char)(u_val < 0 ? 0 : (u_val > 255 ? 255 : u_val));
                v[uv_idx] = (unsigned char)(v_val < 0 ? 0 : (v_val > 255 ? 255 : v_val));
            }
        }
    }
}

static int encode_vp8_frame(NativeVP8Encoder *enc, const unsigned char *rgba, unsigned char *out_buf, int max_out, int force_keyframe) {
    if (!enc || !rgba || !out_buf) return 0;

    rgba_to_yuv420(rgba, &enc->raw, enc->width, enc->height);

    vpx_enc_frame_flags_t flags = 0;
    if (force_keyframe) {
        flags |= VPX_EFLAG_FORCE_KF;
    }

    if (vpx_codec_encode(&enc->ctx, &enc->raw, enc->pts++, 1, flags, VPX_DL_REALTIME) != VPX_CODEC_OK) {
        return 0;
    }

    vpx_codec_iter_t iter = NULL;
    const vpx_codec_cx_pkt_t *pkt = vpx_codec_get_cx_data(&enc->ctx, &iter);
    if (pkt && pkt->kind == VPX_CODEC_CX_FRAME_PKT) {
        if ((int)pkt->data.frame.sz <= max_out) {
            memcpy(out_buf, pkt->data.frame.buf, pkt->data.frame.sz);
            return (int)pkt->data.frame.sz;
        }
    }
    return 0;
}

static int encode_vp8_yuv(
    NativeVP8Encoder *enc,
    const unsigned char *y,
    const unsigned char *u,
    const unsigned char *v,
    int stride_y,
    int stride_u,
    int stride_v,
    unsigned char *out_buf,
    int max_out,
    int force_keyframe
) {
    if (!enc || !y || !u || !v || !out_buf) return 0;

    unsigned char *dst_y = enc->raw.planes[VPX_PLANE_Y];
    unsigned char *dst_u = enc->raw.planes[VPX_PLANE_U];
    unsigned char *dst_v = enc->raw.planes[VPX_PLANE_V];
    int dst_stride_y = enc->raw.stride[VPX_PLANE_Y];
    int dst_stride_u = enc->raw.stride[VPX_PLANE_U];
    int dst_stride_v = enc->raw.stride[VPX_PLANE_V];
    int h = enc->height;
    int w = enc->width;

    for (int r = 0; r < h; r++) {
        memcpy(dst_y + r * dst_stride_y, y + r * stride_y, w);
    }
    int uv_h = (h + 1) / 2;
    int uv_w = (w + 1) / 2;
    for (int r = 0; r < uv_h; r++) {
        memcpy(dst_u + r * dst_stride_u, u + r * stride_u, uv_w);
        memcpy(dst_v + r * dst_stride_v, v + r * stride_v, uv_w);
    }

    vpx_enc_frame_flags_t flags = 0;
    if (force_keyframe) {
        flags |= VPX_EFLAG_FORCE_KF;
    }

    if (vpx_codec_encode(&enc->ctx, &enc->raw, enc->pts++, 1, flags, VPX_DL_REALTIME) != VPX_CODEC_OK) {
        return 0;
    }

    vpx_codec_iter_t iter = NULL;
    const vpx_codec_cx_pkt_t *pkt = vpx_codec_get_cx_data(&enc->ctx, &iter);
    if (pkt && pkt->kind == VPX_CODEC_CX_FRAME_PKT) {
        if ((int)pkt->data.frame.sz <= max_out) {
            memcpy(out_buf, pkt->data.frame.buf, pkt->data.frame.sz);
            return (int)pkt->data.frame.sz;
        }
    }
    return 0;
}
*/
import "C"
import (
	"bytes"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"unsafe"
)

type NativeVP8Decoder struct {
	dec *C.NativeVP8Decoder
}

func NewNativeVP8Decoder() (*NativeVP8Decoder, error) {
	dec := C.create_vp8_decoder()
	if dec == nil {
		return nil, fmt.Errorf("failed to create VP8 decoder")
	}
	return &NativeVP8Decoder{dec: dec}, nil
}

func (d *NativeVP8Decoder) DecodeJPEG(payload []byte) ([]byte, int, int, error) {
	if len(payload) == 0 || d.dec == nil {
		return nil, 0, 0, nil
	}

	var width, height C.int
	var yPtr, uPtr, vPtr *C.uchar
	var strideY, strideU, strideV C.int

	ok := C.decode_vp8_frame(
		d.dec,
		(*C.uchar)(unsafe.Pointer(&payload[0])),
		C.uint(len(payload)),
		&width,
		&height,
		&yPtr,
		&uPtr,
		&vPtr,
		&strideY,
		&strideU,
		&strideV,
	)

	if ok == 0 || width <= 0 || height <= 0 {
		return nil, 0, 0, nil
	}

	w := int(width)
	h := int(height)

	// Wrap YUV planes in image.YCbCr
	yLen := int(strideY) * h
	cbLen := int(strideU) * (h / 2)
	crLen := int(strideV) * (h / 2)

	img := &image.YCbCr{
		Y:              unsafe.Slice((*byte)(yPtr), yLen),
		Cb:             unsafe.Slice((*byte)(uPtr), cbLen),
		Cr:             unsafe.Slice((*byte)(vPtr), crLen),
		YStride:        int(strideY),
		CStride:        int(strideU),
		SubsampleRatio: image.YCbCrSubsampleRatio420,
		Rect:           image.Rect(0, 0, w, h),
	}

	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: 70}); err != nil {
		return nil, 0, 0, err
	}

	return buf.Bytes(), w, h, nil
}

func (d *NativeVP8Decoder) Close() {
	if d.dec != nil {
		C.destroy_vp8_decoder(d.dec)
		d.dec = nil
	}
}

type NativeVP8Encoder struct {
	enc *C.NativeVP8Encoder
}

func NewNativeVP8Encoder(width, height, fps, bitrateKbps int) (*NativeVP8Encoder, error) {
	enc := C.create_vp8_encoder(C.int(width), C.int(height), C.int(fps), C.int(bitrateKbps))
	if enc == nil {
		return nil, fmt.Errorf("failed to create VP8 encoder")
	}
	return &NativeVP8Encoder{enc: enc}, nil
}

func (e *NativeVP8Encoder) EncodeRGBA(rgba []byte, out []byte, forceKeyframe bool) (int, error) {
	if len(rgba) == 0 || len(out) == 0 || e.enc == nil {
		return 0, nil
	}
	key := C.int(0)
	if forceKeyframe {
		key = 1
	}
	n := C.encode_vp8_frame(
		e.enc,
		(*C.uchar)(unsafe.Pointer(&rgba[0])),
		(*C.uchar)(unsafe.Pointer(&out[0])),
		C.int(len(out)),
		key,
	)
	if n <= 0 {
		return 0, fmt.Errorf("VP8 encode failed")
	}
	return int(n), nil
}

func (e *NativeVP8Encoder) EncodeYCbCr(y, u, v []byte, strideY, strideU, strideV int, out []byte, forceKeyframe bool) (int, error) {
	if len(y) == 0 || len(u) == 0 || len(v) == 0 || len(out) == 0 || e.enc == nil {
		return 0, nil
	}
	key := C.int(0)
	if forceKeyframe {
		key = 1
	}
	n := C.encode_vp8_yuv(
		e.enc,
		(*C.uchar)(unsafe.Pointer(&y[0])),
		(*C.uchar)(unsafe.Pointer(&u[0])),
		(*C.uchar)(unsafe.Pointer(&v[0])),
		C.int(strideY),
		C.int(strideU),
		C.int(strideV),
		(*C.uchar)(unsafe.Pointer(&out[0])),
		C.int(len(out)),
		key,
	)
	if n <= 0 {
		return 0, fmt.Errorf("VP8 YUV encode failed")
	}
	return int(n), nil
}

// EncodeImage automatically encodes image.Image directly without intermediate allocations or format conversions.
func (e *NativeVP8Encoder) EncodeImage(img image.Image, out []byte, forceKeyframe bool) (int, error) {
	if img == nil || len(out) == 0 || e.enc == nil {
		return 0, nil
	}
	key := C.int(0)
	if forceKeyframe {
		key = 1
	}

	switch m := img.(type) {
	case *image.YCbCr:
		if m.SubsampleRatio == image.YCbCrSubsampleRatio420 && len(m.Y) > 0 && len(m.Cb) > 0 && len(m.Cr) > 0 {
			n := C.encode_vp8_yuv(
				e.enc,
				(*C.uchar)(unsafe.Pointer(&m.Y[0])),
				(*C.uchar)(unsafe.Pointer(&m.Cb[0])),
				(*C.uchar)(unsafe.Pointer(&m.Cr[0])),
				C.int(m.YStride),
				C.int(m.CStride),
				C.int(m.CStride),
				(*C.uchar)(unsafe.Pointer(&out[0])),
				C.int(len(out)),
				key,
			)
			if n > 0 {
				return int(n), nil
			}
			return 0, fmt.Errorf("VP8 YUV encode failed")
		}
	case *image.RGBA:
		if len(m.Pix) > 0 {
			n := C.encode_vp8_frame(
				e.enc,
				(*C.uchar)(unsafe.Pointer(&m.Pix[0])),
				(*C.uchar)(unsafe.Pointer(&out[0])),
				C.int(len(out)),
				key,
			)
			if n > 0 {
				return int(n), nil
			}
			return 0, fmt.Errorf("VP8 RGBA encode failed")
		}
	case *image.NRGBA:
		if len(m.Pix) > 0 {
			n := C.encode_vp8_frame(
				e.enc,
				(*C.uchar)(unsafe.Pointer(&m.Pix[0])),
				(*C.uchar)(unsafe.Pointer(&out[0])),
				C.int(len(out)),
				key,
			)
			if n > 0 {
				return int(n), nil
			}
			return 0, fmt.Errorf("VP8 NRGBA encode failed")
		}
	}

	// Fallback for custom image types
	rgbaBytes, _, _ := ImageToRGBABytes(img)
	return e.EncodeRGBA(rgbaBytes, out, forceKeyframe)
}

func (e *NativeVP8Encoder) Close() {
	if e.enc != nil {
		C.destroy_vp8_encoder(e.enc)
		e.enc = nil
	}
}

// Convert image.Image to RGBA bytes
func ImageToRGBABytes(img image.Image) ([]byte, int, int) {
	bounds := img.Bounds()
	w, h := bounds.Dx(), bounds.Dy()
	if rgba, ok := img.(*image.RGBA); ok && rgba.Stride == w*4 {
		return rgba.Pix, w, h
	}
	if nrgba, ok := img.(*image.NRGBA); ok && nrgba.Stride == w*4 {
		return nrgba.Pix, w, h
	}
	rgba := make([]byte, w*h*4)
	idx := 0
	for y := bounds.Min.Y; y < bounds.Max.Y; y++ {
		for x := bounds.Min.X; x < bounds.Max.X; x++ {
			c := color.RGBAModel.Convert(img.At(x, y)).(color.RGBA)
			rgba[idx] = c.R
			rgba[idx+1] = c.G
			rgba[idx+2] = c.B
			rgba[idx+3] = c.A
			idx += 4
		}
	}
	return rgba, w, h
}
