from pathlib import Path
p=Path('android/app/src/test/java/com/matelink/ui/screens/charges/ChargesRoomCostConsumerTest.kt')
s=p.read_text()
a='            vm.loadMore()\n            awaitCharges(vm, listOf(71))\n'
b='''            vm.loadMore()
            // Oct 19 minus 30 days is Sep 19: the Sep 20 receipt now belongs to the window.
            // Wait for the completed wider-window projection, not the transient old list.
            awaitCharges(vm, listOf(71, 72))
            assertEquals(setOf(71, 72), vm.uiState.value.charges.map { it.chargeId }.toSet())
            assertEquals(2, vm.uiState.value.summary.totalCharges)
            assertEquals(5.70, vm.uiState.value.summary.totalCost ?: Double.NaN, 0.0001)
'''
assert s.count(a)==1
s=s.replace(a,b)
a='!vm.uiState.value.isLoading && !vm.uiState.value.isFilterLoading)'
assert s.count(a)==1
s=s.replace(a,'!vm.uiState.value.isLoading && !vm.uiState.value.isFilterLoading && !vm.uiState.value.isLoadingMore)')
p.write_text(s)
p=Path('docs/REVIEW-20261103-FULL-GATE-CORRECTIONS.md')
p.write_text(p.read_text()+'''\nThe independent Mini branch full compatibility gate exposed an inherited pagination assertion that could return on a transient old list. Expanding Oct 19 by 30 days includes the Sep 20 record, so the test now waits for load-more completion and requires both records, exact total count and 5.70 aggregate. The same correction is applied to this App's matching regression. Neither branch's application behavior or test coverage is disabled.\n''')
print('Completed paging waits and precise expanded-window row/count/amount assertions retained.')
