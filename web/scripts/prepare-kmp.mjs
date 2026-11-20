import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const cwd = fileURLToPath(new URL('../../', import.meta.url))
execFileSync('./gradlew', [':shared:jsBrowserProductionLibraryDistribution', '--no-daemon', '--console=plain'], {
  cwd, stdio: 'inherit',
})
