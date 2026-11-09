package com.ragpeat.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MarkdownPagesTest {

    @Test
    void onlyAgentsThatPreferMarkdownGetIt() {
        assertThat(MarkdownPages.wantsMarkdown("text/markdown")).isTrue();
        assertThat(MarkdownPages.wantsMarkdown("text/markdown, text/html;q=0.8")).isTrue();
        assertThat(MarkdownPages.wantsMarkdown("text/html,application/xhtml+xml,*/*;q=0.8")).isFalse();
        assertThat(MarkdownPages.wantsMarkdown("text/html, text/markdown;q=0.5")).isFalse();
        assertThat(MarkdownPages.wantsMarkdown("*/*")).isFalse();
        assertThat(MarkdownPages.wantsMarkdown(null)).isFalse();
        assertThat(MarkdownPages.wantsMarkdown("text/markdown;;;q=x")).isFalse();
    }

    @Test
    void mapsPublicPagesOnly() {
        assertThat(MarkdownPages.source("/")).isEqualTo("static/md/index.md");
        assertThat(MarkdownPages.source(MarkdownPages.normalize("/en/"))).isEqualTo("static/md/en.md");
        assertThat(MarkdownPages.source("/temas/sal")).isEqualTo("static/temas/sal.html");
        assertThat(MarkdownPages.source("/en/topics/salt")).isEqualTo("static/en/topics/salt.html");
        assertThat(MarkdownPages.source("/temas/../panel")).isNull();
        assertThat(MarkdownPages.source("/panel")).isNull();
    }

    @Test
    void convertsTheMainContent() {
        String html = """
                <html><head><title>T</title></head><body><nav>menu</nav><main>
                <h1>Ray Peat y la sal</h1>
                <p>Sal <a class="topic-cite" href="#fuente-1">1</a> y <strong>sodio</strong>. <a href="/temas">Temas</a></p>
                <ol><li><a href="https://raypeat.com/a.shtml">Salt</a> <span>artículo</span></li></ol>
                <script>alert(1)</script></main></body></html>
                """;
        String md = MarkdownPages.fromHtml(html, "/temas/sal");
        assertThat(md).startsWith("# Ray Peat y la sal\n\nSal [1] y **sodio**. [Temas](https://peatying.com/temas)");
        assertThat(md).contains("1. [Salt](https://raypeat.com/a.shtml) artículo");
        assertThat(md).doesNotContain("menu").doesNotContain("alert");
        assertThat(md).endsWith("Web page: https://peatying.com/temas/sal\n");
    }
}
