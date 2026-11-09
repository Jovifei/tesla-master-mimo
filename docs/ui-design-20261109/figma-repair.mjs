/** Pending repair for the existing MateLink Figma document. NOT APPLIED.
 * Execute in the existing authorized Figma file only after the tool quota gate clears.
 * No network, credentials, production code, or data access. Idempotent for selected chart layers.
 */
async function repairMateLink(figma) {
  const key = 'Otvrx2SzylGWkHBX9lYpAR';
  if (figma.fileKey && figma.fileKey !== key) throw new Error('Wrong Figma document');
  const page = await figma.getNodeByIdAsync('0:1');
  const required = ['6:392','19:65','19:131','19:197','19:371'];
  const targets = await Promise.all(required.map(id => figma.getNodeByIdAsync(id)));
  if (!page || targets.some(n => !n || n.type !== 'FRAME' || n.parent.id !== page.id)) {
    throw new Error('Expected design nodes are missing or moved; inspect before any write');
  }
  await figma.setCurrentPageAsync(page);
  const texts = page.findAllWithCriteria({types:['TEXT']});
  const fonts = new Map();
  for (const t of texts) for (const seg of t.getStyledTextSegments(['fontName'])) {
    fonts.set(JSON.stringify(seg.fontName), seg.fontName);
  }
  for (const font of fonts.values()) await figma.loadFontAsync(font);
  const palette = {
    bg:['#F5F7F9','#111315'],surface:['#FFFFFF','#1B2025'],subtle:['#F0F3F5','#252C33'],
    ink:['#192B32','#F3F7F8'],secondary:['#5B6B73','#B8C7CF'],muted:['#677881','#A7B8C1'],
    brand:['#157A86','#8CCCD2'],onBrand:['#FFFFFF','#083344'],tint:['#DDF0F1','#16454C'],
    border:['#E1E8EC','#35444D'],success:['#047857','#75D8AE'],warning:['#8C4B11','#FFC46B'],
    warningBg:['#FFF4E2','#382716'],error:['#B42318','#FFAAA3'],errorBg:['#FFF0EE','#3B2020'],
    blue:['#2467B3','#8ABEFF'],purple:['#7952A8','#CBA9F0']
  };
  const rgb = h => ({r:parseInt(h.slice(1,3),16)/255,g:parseInt(h.slice(3,5),16)/255,b:parseInt(h.slice(5,7),16)/255});
  const variables = await figma.variables.getLocalVariablesAsync('COLOR');
  const byId = Object.fromEntries(variables.map(v=>[v.id,{name:v.name,dark:v.variableCollectionId==='VariableCollectionId:4:22'}]));
  const changed=[],created=[],removed=[];
  for (const n of page.findAll(()=>true)) {
    let dirty=false;
    for (const prop of ['fills','strokes']) {
      if (!(prop in n) || !Array.isArray(n[prop])) continue;
      let update=false;
      const paints=n[prop].map(p=>{
        const alias=p.type==='SOLID' && p.boundVariables?.color;
        const v=alias && byId[alias.id];
        if (!v || !palette[v.name]) return p;
        update=true;
        return {...p,color:rgb(palette[v.name][v.dark?1:0])};
      });
      if (update) {n[prop]=paints;dirty=true;}
    }
    if(dirty) changed.push(n.id);
  }
  const rows=[
    ['T03','19:65',[2.73,2.74,2.71,2.72,2.75,2.74,2.75]],
    ['T04','19:131',[2.75,2.76,2.73,2.73,2.77,2.74,2.75]],
    ['T05','19:197',[2.78,2.79,2.76,2.75,2.77,2.78,2.78]]
  ];
  for (const [key,id,values] of rows) {
    const frame=await figma.getNodeByIdAsync(id);
    if (!frame.name.startsWith(key+' ·')) throw new Error('Frame identity mismatch');
    const chart=frame.findAll(n=>n.name==='Chart · illustrative only')[0];
    if (!chart) throw new Error('Expected chart missing');
    const points=values.map((v,i)=>[34+276*i/(values.length-1),180-(v-2.5)/.5*160]);
    const d=points.map((p,i)=>(i?'L':'M')+p.join(' ')).join(' ');
    const svg=figma.createNodeFromSvg('<svg width="320" height="210" viewBox="0 0 320 210"><path d="'+d+'" fill="none" stroke="#157A86" stroke-width="3" stroke-linecap="round"/>'+points.map(p=>'<circle cx="'+p[0]+'" cy="'+p[1]+'" r="3" fill="#157A86"/>').join('')+'</svg>');
    for (const old of chart.children.filter(n=>n.name.startsWith('Series'))) {removed.push(old.id);old.remove();}
    chart.appendChild(svg);svg.name='Series 0';created.push(svg.id,...svg.findAll(()=>true).map(n=>n.id));
  }
  return {createdNodeIds:created,mutatedNodeIds:changed,removedNodeIds:removed,
    requiredNextGate:'Render A01, T01, T02-T05, A01D, T01D, C03, M01/M02/M06; inspect actual pixels. No acceptance from script execution alone.'};
}
await repairMateLink(figma);
