package gardening.adapters.sqlite

import java.time.format.DateTimeFormatterBuilder

private[sqlite] val sqliteInstant = DateTimeFormatterBuilder().appendInstant(9).toFormatter
