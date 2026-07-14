// Minimal MessagePack encoder/decoder (ES module)
// Supports: null, bool, int (0..2^53), float64, string (UTF-8), binary (Uint8Array), array, map
// No extensions, no timestamps, no bigint — sufficient for messenger protocol

const encoder = new TextEncoder();
const decoder = new TextDecoder();

function encodeValue(val, buf) {
  if (val === null || val === undefined) {
    buf.push(0xc0);
    return;
  }
  if (val === false) { buf.push(0xc2); return; }
  if (val === true)  { buf.push(0xc3); return; }

  if (val instanceof Uint8Array) {
    const n = val.length;
    if (n <= 0xff) {
      buf.push(0xc4, n);
    } else if (n <= 0xffff) {
      buf.push(0xc5, (n >> 8) & 0xff, n & 0xff);
    } else {
      buf.push(0xc6, (n >> 24) & 0xff, (n >> 16) & 0xff, (n >> 8) & 0xff, n & 0xff);
    }
    for (let i = 0; i < n; i++) buf.push(val[i]);
    return;
  }

  if (typeof val === 'number') {
    if (Number.isInteger(val) && val >= 0 && val <= 0x7f) {
      buf.push(val); return;
    }
    if (Number.isInteger(val) && val < 0 && val >= -32) {
      buf.push(0xe0 | (val + 32)); return;
    }
    if (Number.isInteger(val)) {
      if (val >= 0) {
        if (val <= 0xff)       { buf.push(0xcc, val); return; }
        if (val <= 0xffff)     { buf.push(0xcd, (val>>8)&0xff, val&0xff); return; }
        if (val <= 0xffffffff) {
          buf.push(0xce, (val>>>24)&0xff, (val>>>16)&0xff, (val>>>8)&0xff, val&0xff);
          return;
        }
        // uint64 via float64 fallthrough
      } else {
        if (val >= -128)   { buf.push(0xd0, val & 0xff); return; }
        if (val >= -32768) { buf.push(0xd1, (val>>8)&0xff, val&0xff); return; }
        if (val >= -2147483648) {
          buf.push(0xd2, (val>>24)&0xff, (val>>16)&0xff, (val>>8)&0xff, val&0xff);
          return;
        }
      }
    }
    // float64
    const tmp = new DataView(new ArrayBuffer(9));
    tmp.setUint8(0, 0xcb);
    tmp.setFloat64(1, val);
    for (let i = 0; i < 9; i++) buf.push(tmp.getUint8(i));
    return;
  }

  if (typeof val === 'string') {
    const bytes = encoder.encode(val);
    const n = bytes.length;
    if (n <= 31) {
      buf.push(0xa0 | n);
    } else if (n <= 0xff) {
      buf.push(0xd9, n);
    } else if (n <= 0xffff) {
      buf.push(0xda, (n>>8)&0xff, n&0xff);
    } else {
      buf.push(0xdb, (n>>24)&0xff, (n>>16)&0xff, (n>>8)&0xff, n&0xff);
    }
    for (let i = 0; i < n; i++) buf.push(bytes[i]);
    return;
  }

  if (Array.isArray(val)) {
    const n = val.length;
    if (n <= 15)     buf.push(0x90 | n);
    else if (n <= 0xffff) buf.push(0xdc, (n>>8)&0xff, n&0xff);
    else             buf.push(0xdd, (n>>24)&0xff, (n>>16)&0xff, (n>>8)&0xff, n&0xff);
    for (const item of val) encodeValue(item, buf);
    return;
  }

  if (typeof val === 'object') {
    const keys = Object.keys(val);
    const n = keys.length;
    if (n <= 15)     buf.push(0x80 | n);
    else if (n <= 0xffff) buf.push(0xde, (n>>8)&0xff, n&0xff);
    else             buf.push(0xdf, (n>>24)&0xff, (n>>16)&0xff, (n>>8)&0xff, n&0xff);
    for (const k of keys) {
      encodeValue(k, buf);
      encodeValue(val[k], buf);
    }
    return;
  }
}

export function encode(val) {
  const buf = [];
  encodeValue(val, buf);
  return new Uint8Array(buf);
}

function decodeValue(view, pos) {
  const b = view.getUint8(pos.i++);

  // positive fixint
  if ((b & 0x80) === 0) return b;
  // negative fixint
  if ((b & 0xe0) === 0xe0) return b - 256;
  // fixstr
  if ((b & 0xe0) === 0xa0) {
    const len = b & 0x1f;
    const str = decoder.decode(new Uint8Array(view.buffer, pos.i, len));
    pos.i += len; return str;
  }
  // fixarray
  if ((b & 0xf0) === 0x90) {
    const n = b & 0x0f;
    const arr = [];
    for (let i = 0; i < n; i++) arr.push(decodeValue(view, pos));
    return arr;
  }
  // fixmap
  if ((b & 0xf0) === 0x80) {
    const n = b & 0x0f;
    const obj = {};
    for (let i = 0; i < n; i++) { const k = decodeValue(view, pos); obj[k] = decodeValue(view, pos); }
    return obj;
  }

  switch (b) {
    case 0xc0: return null;
    case 0xc2: return false;
    case 0xc3: return true;
    // bin8/16/32
    case 0xc4: { const n=view.getUint8(pos.i++); const r=new Uint8Array(view.buffer, pos.i, n); pos.i+=n; return r; }
    case 0xc5: { const n=view.getUint16(pos.i); pos.i+=2; const r=new Uint8Array(view.buffer, pos.i, n); pos.i+=n; return r; }
    case 0xc6: { const n=view.getUint32(pos.i); pos.i+=4; const r=new Uint8Array(view.buffer, pos.i, n); pos.i+=n; return r; }
    // float64
    case 0xcb: { const v=view.getFloat64(pos.i); pos.i+=8; return v; }
    // uint8/16/32
    case 0xcc: return view.getUint8(pos.i++);
    case 0xcd: { const v=view.getUint16(pos.i); pos.i+=2; return v; }
    case 0xce: { const v=view.getUint32(pos.i); pos.i+=4; return v; }
    // int8/16/32
    case 0xd0: return view.getInt8(pos.i++);
    case 0xd1: { const v=view.getInt16(pos.i); pos.i+=2; return v; }
    case 0xd2: { const v=view.getInt32(pos.i); pos.i+=4; return v; }
    // str8/16/32
    case 0xd9: { const n=view.getUint8(pos.i++); const s=decoder.decode(new Uint8Array(view.buffer,pos.i,n)); pos.i+=n; return s; }
    case 0xda: { const n=view.getUint16(pos.i); pos.i+=2; const s=decoder.decode(new Uint8Array(view.buffer,pos.i,n)); pos.i+=n; return s; }
    case 0xdb: { const n=view.getUint32(pos.i); pos.i+=4; const s=decoder.decode(new Uint8Array(view.buffer,pos.i,n)); pos.i+=n; return s; }
    // array16/32
    case 0xdc: { const n=view.getUint16(pos.i); pos.i+=2; const a=[]; for(let i=0;i<n;i++) a.push(decodeValue(view,pos)); return a; }
    case 0xdd: { const n=view.getUint32(pos.i); pos.i+=4; const a=[]; for(let i=0;i<n;i++) a.push(decodeValue(view,pos)); return a; }
    // map16/32
    case 0xde: { const n=view.getUint16(pos.i); pos.i+=2; const o={}; for(let i=0;i<n;i++){const k=decodeValue(view,pos);o[k]=decodeValue(view,pos);} return o; }
    case 0xdf: { const n=view.getUint32(pos.i); pos.i+=4; const o={}; for(let i=0;i<n;i++){const k=decodeValue(view,pos);o[k]=decodeValue(view,pos);} return o; }
    default: throw new Error(`msgpack: unknown byte 0x${b.toString(16)}`);
  }
}

export function decode(buf) {
  const view = new DataView(buf instanceof ArrayBuffer ? buf : buf.buffer, buf.byteOffset ?? 0, buf.byteLength);
  return decodeValue(view, { i: 0 });
}
