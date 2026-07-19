#!/usr/bin/env node

const MAX_SAFE_INTEGER = Number.MAX_SAFE_INTEGER

function compareCodePoints(left, right) {
  const a = Array.from(left, (character) => character.codePointAt(0))
  const b = Array.from(right, (character) => character.codePointAt(0))
  const length = Math.min(a.length, b.length)
  for (let index = 0; index < length; index += 1) {
    if (a[index] !== b[index]) return a[index] - b[index]
  }
  return a.length - b.length
}

function validate(value, path = '$') {
  if (value === null || typeof value === 'boolean') return
  if (typeof value === 'number') {
    if (!Number.isSafeInteger(value)) throw new Error(`JSON_INTEGER_PROFILE_REQUIRED: ${path}`)
    if (Object.is(value, -0)) throw new Error(`JSON_NEGATIVE_ZERO_FORBIDDEN: ${path}`)
    return
  }
  if (typeof value === 'string') {
    for (let index = 0; index < value.length; index += 1) {
      const unit = value.charCodeAt(index)
      if (unit >= 0xd800 && unit <= 0xdbff) {
        const next = value.charCodeAt(index + 1)
        if (!(next >= 0xdc00 && next <= 0xdfff)) throw new Error(`JSON_LONE_SURROGATE_FORBIDDEN: ${path}`)
        index += 1
      } else if (unit >= 0xdc00 && unit <= 0xdfff) {
        throw new Error(`JSON_LONE_SURROGATE_FORBIDDEN: ${path}`)
      }
    }
    return
  }
  if (Array.isArray(value)) {
    value.forEach((item, index) => validate(item, `${path}[${index}]`))
    return
  }
  if (typeof value === 'object') {
    Object.entries(value).forEach(([key, item]) => {
      validate(key, `${path}.<key>`)
      validate(item, `${path}.${key}`)
    })
    return
  }
  throw new Error(`JSON_VALUE_TYPE_FORBIDDEN: ${path}`)
}

function canonical(value) {
  validate(value)
  if (value === null || typeof value === 'boolean' || typeof value === 'number' || typeof value === 'string') {
    return JSON.stringify(value)
  }
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`
  return `{${Object.keys(value)
    .sort(compareCodePoints)
    .map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`)
    .join(',')}}`
}

function parseStrict(input) {
  let index = 0
  const fail = (code) => { throw new Error(`${code}: byte-${Buffer.byteLength(input.slice(0, index), 'utf8')}`) }
  const whitespace = () => { while (/[\t\n\r ]/.test(input[index] || '')) index += 1 }
  const string = () => {
    if (input[index] !== '"') fail('JSON_STRING_REQUIRED')
    const start = index
    index += 1
    while (index < input.length) {
      const character = input[index]
      if (character === '"') {
        index += 1
        try { return JSON.parse(input.slice(start, index)) } catch { fail('JSON_STRING_INVALID') }
      }
      if (character === '\\') {
        index += 1
        if (input[index] === 'u') {
          if (!/^[0-9a-fA-F]{4}$/.test(input.slice(index + 1, index + 5))) fail('JSON_ESCAPE_INVALID')
          index += 5
          continue
        }
        if (!/["\\/bfnrt]/.test(input[index] || '')) fail('JSON_ESCAPE_INVALID')
      } else if (character.charCodeAt(0) < 0x20) {
        fail('JSON_CONTROL_CHARACTER_FORBIDDEN')
      }
      index += 1
    }
    fail('JSON_STRING_UNTERMINATED')
  }
  const value = () => {
    whitespace()
    if (input[index] === '"') return string()
    if (input[index] === '{') {
      index += 1
      whitespace()
      const result = {}
      const keys = new Set()
      if (input[index] === '}') { index += 1; return result }
      while (true) {
        whitespace()
        const key = string()
        if (keys.has(key)) fail(`JSON_DUPLICATE_KEY: ${key}`)
        keys.add(key)
        whitespace()
        if (input[index] !== ':') fail('JSON_COLON_REQUIRED')
        index += 1
        result[key] = value()
        whitespace()
        if (input[index] === '}') { index += 1; return result }
        if (input[index] !== ',') fail('JSON_COMMA_REQUIRED')
        index += 1
      }
    }
    if (input[index] === '[') {
      index += 1
      whitespace()
      const result = []
      if (input[index] === ']') { index += 1; return result }
      while (true) {
        result.push(value())
        whitespace()
        if (input[index] === ']') { index += 1; return result }
        if (input[index] !== ',') fail('JSON_COMMA_REQUIRED')
        index += 1
      }
    }
    for (const [literal, parsed] of [['true', true], ['false', false], ['null', null]]) {
      if (input.startsWith(literal, index)) { index += literal.length; return parsed }
    }
    const token = input.slice(index).match(/^-?(?:0|[1-9][0-9]*)/)?.[0]
    if (!token) fail('JSON_VALUE_INVALID')
    const following = input[index + token.length]
    if (following === '.' || following === 'e' || following === 'E') fail('JSON_INTEGER_PROFILE_REQUIRED')
    index += token.length
    if (token === '-0') fail('JSON_NEGATIVE_ZERO_FORBIDDEN')
    const parsed = Number(token)
    if (!Number.isSafeInteger(parsed)) fail('JSON_INTEGER_OUT_OF_RANGE')
    return parsed
  }
  const parsed = value()
  whitespace()
  if (index !== input.length) fail('JSON_TRAILING_CONTENT')
  return parsed
}

let input = ''
process.stdin.setEncoding('utf8')
process.stdin.on('data', (chunk) => { input += chunk })
process.stdin.on('end', () => {
  try {
    process.stdout.write(canonical(parseStrict(input)))
  } catch (error) {
    process.stderr.write(`${error.message}\n`)
    process.exitCode = 1
  }
})
