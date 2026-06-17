from ankienrich import config


def test_image_queue_constants_exist():
    assert config.NEEDS_IMAGE_TAG == "ankienrich::needs-image"
    assert config.NO_IMAGE_TAG == "ankienrich::no-image"
    assert config.IMAGE_BUTTON == "🖼 画像"
