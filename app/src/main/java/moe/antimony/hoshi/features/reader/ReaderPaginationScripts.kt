package moe.antimony.hoshi.features.reader

internal enum class ReaderNavigationDirection(val jsValue: String) {
    Forward("forward"),
    Backward("backward"),
}

internal object ReaderPaginationScripts {
    fun paginateInvocation(direction: ReaderNavigationDirection): String =
        "window.hoshiReader.paginate('${direction.jsValue}')"

    fun didScroll(result: String?): Boolean =
        result?.trim()?.trim('"') == "scrolled"

    fun shellScript(initialProgress: Double = 0.0): String = """
        <script>
        window.hoshiReader = {
          pageHeight: 0,
          pageWidth: 0,
          isVertical: function() {
            return window.getComputedStyle(document.body).writingMode === "vertical-rl";
          },
          getScrollContext: function() {
            var vertical = this.isVertical();
            var scrollEl = document.body;
            var pageSize = Math.max(1, vertical ? (this.pageHeight || window.innerHeight) : (this.pageWidth || window.innerWidth));
            var totalSize = vertical ? scrollEl.scrollHeight : scrollEl.scrollWidth;
            var maxScroll = Math.max(0, totalSize - pageSize);
            return { vertical: vertical, scrollEl: scrollEl, pageSize: pageSize, maxScroll: maxScroll };
          },
          getPagePosition: function(context) {
            return context.vertical ? context.scrollEl.scrollTop : context.scrollEl.scrollLeft;
          },
          setPagePosition: function(context, position) {
            var clamped = Math.min(Math.max(0, position), context.maxScroll);
            if (context.vertical) {
              context.scrollEl.scrollTop = clamped;
            } else {
              context.scrollEl.scrollLeft = clamped;
            }
            return clamped;
          },
          restoreProgress: function(progress) {
            var context = this.getScrollContext();
            if (context.pageSize <= 0 || progress <= 0) {
              this.setPagePosition(context, 0);
              return;
            }
            if (progress >= 0.99) {
              var lastPage = Math.floor(context.maxScroll / context.pageSize) * context.pageSize;
              this.setPagePosition(context, Math.max(0, lastPage));
              return;
            }
            var target = Math.floor((context.maxScroll * progress) / context.pageSize) * context.pageSize;
            this.setPagePosition(context, target);
          },
          paginate: function(direction) {
            var context = this.getScrollContext();
            if (context.pageSize <= 0) return "limit";
            var currentScroll = this.getPagePosition(context);
            var maxAlignedScroll = Math.floor(context.maxScroll / context.pageSize) * context.pageSize;
            if (direction === "forward") {
              if ((currentScroll + context.pageSize) <= (maxAlignedScroll + 1)) {
                var targetForward = Math.round((currentScroll + context.pageSize) / context.pageSize) * context.pageSize;
                this.setPagePosition(context, targetForward);
                return "scrolled";
              }
              return "limit";
            } else {
              if (currentScroll > 0) {
                var targetBack = Math.round((currentScroll - context.pageSize) / context.pageSize) * context.pageSize;
                this.setPagePosition(context, targetBack);
                return "scrolled";
              }
              return "limit";
            }
          }
        };
        window.addEventListener('load', function() {
          var viewport = document.querySelector('meta[name="viewport"]');
          if (viewport) { viewport.remove(); }
          var newViewport = document.createElement('meta');
          newViewport.name = 'viewport';
          newViewport.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
          document.head.appendChild(newViewport);
          var pageHeight = window.innerHeight + ${ReaderLayoutDefaults.bottomOverlapPx};
          var pageWidth = window.innerWidth;
          document.documentElement.style.setProperty('--page-height', pageHeight + 'px');
          document.documentElement.style.setProperty('--page-width', pageWidth + 'px');
          document.documentElement.style.setProperty('--hoshi-image-max-width', Math.max(1, Math.floor(pageWidth * ${ReaderLayoutDefaults.imageWidthViewportRatio})) + 'px');
          document.documentElement.style.setProperty('--hoshi-image-max-height', Math.max(1, pageHeight - ${ReaderLayoutDefaults.bottomOverlapPx}) + 'px');
          window.hoshiReader.pageHeight = pageHeight;
          window.hoshiReader.pageWidth = pageWidth;
          var imagePromises = Array.from(document.querySelectorAll('img')).map(function(img) {
            return new Promise(function(resolve) {
              var isGaiji = img.classList.contains('gaiji') || img.classList.contains('gaiji-line');
              var mark = function() {
                if (!isGaiji && (img.naturalWidth > 256 || img.naturalHeight > 256)) {
                  img.classList.add('block-img');
                }
                resolve();
              };
              if (img.complete && img.naturalWidth > 0) {
                mark();
              } else {
                img.onload = mark;
                img.onerror = function() { resolve(); };
              }
            });
          });
          var spacer = document.createElement('div');
          spacer.style.height = '${ReaderLayoutDefaults.trailingSpacerHeightCss}';
          spacer.style.width = '${ReaderLayoutDefaults.trailingSpacerWidthCss}';
          spacer.style.display = 'block';
          spacer.style.breakInside = 'avoid';
          document.body.appendChild(spacer);
          Promise.all(imagePromises).then(function() {
            return new Promise(function(resolve) { setTimeout(resolve, 50); });
          }).then(function() {
            window.hoshiReader.restoreProgress($initialProgress);
          });
        });
        </script>
    """.trimIndent()
}
