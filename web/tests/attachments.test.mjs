import test from 'node:test'
import assert from 'node:assert/strict'
import { fitImage } from '../src/attachments.js'

test('fit images without upscaling or rounding thin edges to zero', () => {
  assert.deepEqual(fitImage(800, 1200, 4096), [800, 1200])
  assert.deepEqual(fitImage(8192, 4096, 4096), [4096, 2048])
  assert.deepEqual(fitImage(1, 8192, 4096), [1, 4096])
})
