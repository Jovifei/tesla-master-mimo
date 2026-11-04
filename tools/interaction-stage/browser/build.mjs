/** Uses only the owner's locked Mini tooling; does not install or alter any package. */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'
import { execFileSync } from 'node:child_process'
const here=path.dirname(fileURLToPath(import.meta.url)),root=path.resolve(here,'../../..')
const require=createRequire(import.meta.url)
const esbuild=require(path.join(root,'miniprogram/test-tooling/node_modules/esbuild'))
const out=path.join(root,'visual-browser');fs.mkdirSync(out,{recursive:true})
// Extract exactly the existing production loading CSS; no separate mock loading design.
const styles=fs.readFileSync(path.join(root,'miniprogram/src/app.scss'),'utf8')
const first=styles.indexOf('.loading-indicator {'),last=styles.indexOf('/* Layout values copied',first)
if(first<0||last<first)throw new Error('Loading source block changed; inspect before rebuilding fixture')
const loading=path.join(here,'loading-source.css');fs.writeFileSync(loading,styles.slice(first,last))
try{
  await esbuild.build({entryPoints:[path.join(here,'fixture.tsx')],bundle:true,outdir:out,entryNames:'fixture',
    jsx:'automatic',format:'iife',platform:'browser',target:'es2020',metafile:true,
    nodePaths:[path.join(root,'miniprogram/node_modules')],
    loader:{'.png':'file','.scss':'css'},assetNames:'assets/[name]-[hash]',
    define:{'process.env.NODE_ENV':'"production"'},
    plugins:[{name:'test-only-taro-dom',setup(build){build.onResolve({filter:/^@tarojs\/(components|taro)$/},()=>({path:path.join(here,'platform.tsx')}))}}],
  }).then(result=>fs.writeFileSync(path.join(out,'modules.json'),JSON.stringify(result.metafile,null,2)))
  fs.writeFileSync(path.join(out,'index.html'),'<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>MateLink synthetic interaction components</title><link rel="stylesheet" href="fixture.css"><div id="root"></div><script src="fixture.js"></script></html>')
  const sha=execFileSync('git',['rev-parse','HEAD','HEAD^{tree}'],{cwd:root,encoding:'utf8'})
  const staged=execFileSync('git',['write-tree'],{cwd:root,encoding:'utf8'})
  fs.writeFileSync(path.join(out,'SOURCE.txt'),sha+'STAGED_TREE\n'+staged+'actual React consumers; DOM adapter is not native WeChat\n')
  console.log('Built actual component browser fixture without credentials or provider calls')
}finally{fs.rmSync(loading,{force:true})}
