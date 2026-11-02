from pathlib import Path
import ast
# Correct the guarded source-edit script before execution; no assertion is bypassed.
# The three obsolete call patterns below were detected by replay against the complete fixed checkout.
p=Path('tools/remote-review/mini-20261103.py')
tree=ast.parse(p.read_text())
obsolete={
 '  if (charge.cost == null) return \'--\'',
 "  if (charge.costProvenance === 'manual') return '手动费用'",
 "  const displayedCosts = costScope ? overlayChargeCosts(Taro, costScope, filteredCharges.filter(isAppChargeAnalysisEligible), chargeEstimatePrice) : []\n  const costs = chargeCosts(displayedCosts)"
}
removed=[]
class CorrectCalls(ast.NodeTransformer):
 def visit_Expr(self,node):
  c=node.value
  if isinstance(c,ast.Call) and isinstance(c.func,ast.Name) and c.func.id=='rep' and len(c.args)>1 and isinstance(c.args[1],ast.Constant) and c.args[1].value in obsolete:
   removed.append(c.args[1].value); return None
  return self.generic_visit(node)
tree=CorrectCalls().visit(tree); ast.fix_missing_locations(tree)
assert set(removed)==obsolete and len(removed)==3
scope={}; exec(compile(tree,str(p),'exec'),scope)
rep=scope['rep']
p='miniprogram/src/pages/charges/index.tsx'
rep(p,"  if (charge.costProvenance === 'free') return { value: '免费' }", """  if (charge.partialEstimatedCost != null) return charge.currencyCode === 'CNY'
    ? { value: `≈¥${charge.partialEstimatedCost.toFixed(2)}` }
    : { value: `≈${charge.partialEstimatedCost.toFixed(2)}`, unit: charge.currencyCode || '币种未知' }
  if (charge.costProvenance === 'free') return { value: '免费' }""")
rep(p,'`费用按 ${estimateBasis(item)} 估算`', '`费用按 ${estimateBasis(item)} 估算${item.partialEstimatedCost != null ? " · 仅已采集部分，不是整次账单" : ""}`')
rep(p,'`按 ${estimateBasis(costState)} 与合格电池充入量估算`', '`按 ${estimateBasis(costState)} 与${costState.partialEstimatedCost != null ? "已采集部分电量" : "合格电池充入量"}估算，不是实际账单`')
p='miniprogram/src/pages/insights/index.tsx'
rep(p,'  const displayedCosts = filteredCharges.filter(isAppChargeAnalysisEligible).map(displayCharge), costs = chargeCosts(displayedCosts)', '''  const costRows = costScope ? overlayChargeCosts(Taro, costScope, filteredCharges, chargeEstimatePrice(Taro, costScope)) : []
  const displayedCosts = costRows.filter(row => isAppChargeAnalysisEligible(row) && row.costProvenance !== 'estimate')
  const costs = chargeCosts(displayedCosts)
  const fullEstimates = chargeCosts(costRows.filter(row => isAppChargeAnalysisEligible(row) && row.costProvenance === 'estimate' && row.partialEstimatedCost == null))
  const partialEstimates = chargeCosts(costRows.filter(row => row.partialEstimatedCost != null)
    .map(row => ({ ...row, cost: row.partialEstimatedCost! })))''')
print('Exact current Mini page consumers wired; all replacements asserted once.')
