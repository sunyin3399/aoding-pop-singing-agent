package com.sunyin.aodingagent.tools;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class WebSearchToolTest {

//    private static final Logger logger = LoggerFactory.getLogger(WebSearchToolTest.class);

    @Value("${search-api.api-key}")
    private String searchApiKey;

    @Test
    public void testSearchWeb() {
        WebSearchTool tool = new WebSearchTool(searchApiKey);
        String query = "NBA 总决赛";
        // String result = tool.searchWeb(query);
        // Assertions.assertNotNull(result);

        // System.out.println(result);
//        logger.info(result);
    }
}
