import { readFile } from 'node:fs/promises'
import ts from 'typescript'

const moduleUrls = new Map()

/** 将纯游戏模块及其相对TS/JSON依赖转为data URL，不启动Vue或浏览器。 */
async function createModuleUrl(fileUrl) {
  if (moduleUrls.has(fileUrl.href)) return moduleUrls.get(fileUrl.href)
  const task = compileModule(fileUrl)
  moduleUrls.set(fileUrl.href, task)
  return task
}

async function compileModule(fileUrl) {
  const source = await readFile(fileUrl, 'utf8')
  if (fileUrl.pathname.endsWith('.json')) {
    const jsonModule = `export default ${JSON.stringify(JSON.parse(source))};`
    return `data:text/javascript;base64,${Buffer.from(jsonModule).toString('base64')}`
  }
  let compiled = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
  const syntax = ts.createSourceFile('module.js', compiled, ts.ScriptTarget.ES2022, true)
  const replacements = []
  for (const statement of syntax.statements) {
    if (!ts.isImportDeclaration(statement) && !ts.isExportDeclaration(statement)) continue
    const specifier = statement.moduleSpecifier
    if (!specifier || !ts.isStringLiteral(specifier)) continue
    if (!specifier.text.startsWith('.'))
      throw new Error(`纯游戏模块测试只支持相对依赖：${specifier.text}`)
    const path = /\.[cm]?[jt]sx?$|\.json$/.test(specifier.text)
      ? specifier.text
      : `${specifier.text}.ts`
    const dependency = await createModuleUrl(new URL(path, fileUrl))
    replacements.push({
      start: specifier.getStart(syntax) + 1,
      end: specifier.getEnd() - 1,
      value: dependency,
    })
  }
  for (const replacement of replacements.reverse())
    compiled =
      compiled.slice(0, replacement.start) + replacement.value + compiled.slice(replacement.end)
  return `data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`
}

export async function loadGameModule(fileUrl) {
  return import(await createModuleUrl(fileUrl))
}
