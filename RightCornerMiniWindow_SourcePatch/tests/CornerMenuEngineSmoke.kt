import com.lsp.hypersidebar.hook.CornerMenuEngine

private fun checkMove(e: CornerMenuEngine, i: Int) {
    val p = e.center(i)
    e.move(p.first, p.second)
}
fun main() {
    val e = CornerMenuEngine(radius = 200f, hitRadius = 39f, anchorX = 400f, anchorY = 800f)
    check(e.page == CornerMenuEngine.Page.SUGGESTIONS)
    checkMove(e, 2)
    check(e.release(e.center(2).first, e.center(2).second) == CornerMenuEngine.Chosen(CornerMenuEngine.Page.SUGGESTIONS, 2))
    checkMove(e, 5)
    check(e.page == CornerMenuEngine.Page.FAVORITES)
    check(e.highlighted == -1)
    // Move still inside portal after page change; must not toggle back.
    checkMove(e, 5)
    check(e.page == CornerMenuEngine.Page.FAVORITES)
    checkMove(e, 3)
    check(e.release(e.center(3).first, e.center(3).second) == CornerMenuEngine.Chosen(CornerMenuEngine.Page.FAVORITES, 3))
    checkMove(e, 5)
    check(e.page == CornerMenuEngine.Page.SUGGESTIONS)
    checkMove(e, 1)
    check(e.release(400f, 800f) == null)
    checkMove(e, 0)
    check(e.release(e.center(1).first, e.center(1).second) == null)
    println("PASS: suggestions, portal, no bounce, favorites, return, empty release, stale selection")
}
