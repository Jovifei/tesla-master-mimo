/** Presentation only. Compose Color.lerp uses Oklab, not encoded-sRGB interpolation.
 * Matrices implement the standard Oklab transform, without adding a UI dependency.
 * Android reference: https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/package-summary
 */
export function mixSrgbInOklab(from: readonly number[], to: readonly number[], fraction: number): number[] {
  const linear = (v: number) => { const x=v/255;return x<=.04045?x/12.92:((x+.055)/1.055)**2.4 }
  const encoded = (v: number) => v<=.0031308?12.92*v:1.055*Math.max(v,0)**(1/2.4)-.055
  const lab = (rgb: readonly number[]) => {
    const [r,g,b]=rgb.map(linear)
    const l=Math.cbrt(.4122214708*r+.5363325363*g+.0514459929*b)
    const m=Math.cbrt(.2119034982*r+.6806995451*g+.1073969566*b)
    const s=Math.cbrt(.0883024619*r+.2817188376*g+.6299787005*b)
    return [.2104542553*l+.793617785*m-.0040720468*s,1.9779984951*l-2.428592205*m+.4505937099*s,.0259040371*l+.7827717662*m-.808675766*s]
  }
  const a=lab(from),b=lab(to),t=Math.max(0,Math.min(1,fraction))
  const x=a.map((v,i)=>v+(b[i]-v)*t)
  const l=(x[0]+.3963377774*x[1]+.2158037573*x[2])**3
  const m=(x[0]-.1055613458*x[1]-.0638541728*x[2])**3
  const s=(x[0]-.0894841775*x[1]-1.291485548*x[2])**3
  return [4.0767416621*l-3.3077115913*m+.2309699292*s,-1.2684380046*l+2.6097574011*m-.3413193965*s,-.0041960863*l-.7034186147*m+1.707614701*s].map(v=>Math.round(Math.max(0,Math.min(1,encoded(v)))*255))
}
