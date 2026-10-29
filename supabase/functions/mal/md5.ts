// MD5 (RFC 1321), só para os IDs determinísticos (UUID v3, como o UUID.nameUUIDFromBytes da app).
// O crypto.subtle do Deno não tem MD5 e o da std não carrega em todas as versões do runtime.

const SHIFTS = [
  7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
  5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
  4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
  6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
]
const CONSTANTS = Array.from({ length: 64 }, (_, i) => Math.floor(Math.abs(Math.sin(i + 1)) * 2 ** 32) >>> 0)

export function md5(input: Uint8Array): Uint8Array {
  const paddedLength = (((input.length + 8) >> 6) + 1) * 64
  const message = new Uint8Array(paddedLength)
  message.set(input)
  message[input.length] = 0x80
  const view = new DataView(message.buffer)
  view.setUint32(paddedLength - 8, (input.length * 8) >>> 0, true)
  view.setUint32(paddedLength - 4, Math.floor(input.length / 0x20000000), true)

  let a0 = 0x67452301
  let b0 = 0xefcdab89
  let c0 = 0x98badcfe
  let d0 = 0x10325476

  for (let chunk = 0; chunk < paddedLength; chunk += 64) {
    const words = Array.from({ length: 16 }, (_, i) => view.getUint32(chunk + i * 4, true))
    let a = a0
    let b = b0
    let c = c0
    let d = d0
    for (let i = 0; i < 64; i++) {
      let f: number
      let g: number
      if (i < 16) {
        f = (b & c) | (~b & d)
        g = i
      } else if (i < 32) {
        f = (d & b) | (~d & c)
        g = (5 * i + 1) % 16
      } else if (i < 48) {
        f = b ^ c ^ d
        g = (3 * i + 5) % 16
      } else {
        f = c ^ (b | ~d)
        g = (7 * i) % 16
      }
      const sum = (a + f + CONSTANTS[i] + words[g]) >>> 0
      a = d
      d = c
      c = b
      b = (b + ((sum << SHIFTS[i]) | (sum >>> (32 - SHIFTS[i])))) >>> 0
    }
    a0 = (a0 + a) >>> 0
    b0 = (b0 + b) >>> 0
    c0 = (c0 + c) >>> 0
    d0 = (d0 + d) >>> 0
  }

  const digest = new Uint8Array(16)
  const digestView = new DataView(digest.buffer)
  ;[a0, b0, c0, d0].forEach((word, i) => digestView.setUint32(i * 4, word, true))
  return digest
}
