package app.shoalwatch.data.sync

/** PLAYED and FAVOURITE carry [Change.flag]; POSITION carries [Change.positionMs] (0 = off Continue Watching); PLAY is one finished watch of a saved copy, at [Change.occurredAt]. */
enum class ChangeKind { PLAYED, FAVOURITE, POSITION, PLAY }
