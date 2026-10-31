package app.bilidownload.next.parser
import org.junit.Assert.assertEquals
import org.junit.Test
class VideoInputParserTest {
 @Test fun parsesBv(){assertEquals("BV1xx411c7mD",VideoInputParser.parse("https://www.bilibili.com/video/BV1xx411c7mD"))}
 @Test fun parsesAv(){assertEquals("170001",VideoInputParser.parse("av170001"))}
 @Test fun ignoresSearch(){assertEquals(null,VideoInputParser.parse("猫咪日常"))}
}