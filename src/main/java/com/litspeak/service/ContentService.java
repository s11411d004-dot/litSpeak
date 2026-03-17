package com.litspeak.service;

import com.litspeak.supabase.SupabaseClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class ContentService {
    private static final Logger log = LoggerFactory.getLogger(ContentService.class);
    private final SupabaseClient supabaseClient;

    public ContentService(SupabaseClient supabaseClient) {
        this.supabaseClient = supabaseClient;
    }

    public List<Map<String, Object>> getPublishedContent(String type) {
        try {
            return supabaseClient.getPublishedContent(type);
        } catch (IOException e) {
            log.error("Failed to get published content", e);
            return Collections.emptyList();
        }
    }
}
