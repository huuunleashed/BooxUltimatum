package app.booxultimatum.core.sleep

/**
 * A small bundled set, all in the public domain: authors who died long ago, quoted in their own English or in
 * translations published before 1928. One quote a day, the same all day.
 */
object SleepQuotes {
    data class Quote(val text: String, val author: String)

    val ALL = listOf(
        Quote("We are such stuff as dreams are made on, and our little life is rounded with a sleep.", "William Shakespeare"),
        Quote("Sleep that knits up the ravell'd sleeve of care.", "William Shakespeare"),
        Quote("Simplify, simplify.", "Henry David Thoreau"),
        Quote("I went to the woods because I wished to live deliberately.", "Henry David Thoreau"),
        Quote("Adopt the pace of nature: her secret is patience.", "Ralph Waldo Emerson"),
        Quote("The earth laughs in flowers.", "Ralph Waldo Emerson"),
        Quote("Hope is the thing with feathers that perches in the soul.", "Emily Dickinson"),
        Quote("While we are postponing, life speeds by.", "Seneca"),
        Quote("Very little is needed to make a happy life.", "Marcus Aurelius"),
        Quote("I declare after all there is no enjoyment like reading!", "Jane Austen"),
        Quote("A thing of beauty is a joy for ever.", "John Keats"),
        Quote("Begin at the beginning, and go on till you come to the end: then stop.", "Lewis Carroll"),
        Quote("I am no bird; and no net ensnares me.", "Charlotte Brontë"),
        Quote("We are all in the gutter, but some of us are looking at the stars.", "Oscar Wilde"),
        Quote("No man is an island, entire of itself.", "John Donne"),
        Quote("Early to bed and early to rise makes a man healthy, wealthy, and wise.", "Benjamin Franklin"),
    )

    fun forDay(epochDay: Long): Quote = ALL[Math.floorMod(epochDay, ALL.size.toLong()).toInt()]
}
