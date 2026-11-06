package com.glooboo.umessage.activities

/**
 * I53: the thread screen as an Android bubble. A class of its own so the manifest can give it the launch mode bubbles
 * need (document per launch, embeddable, resizeable) without changing how the normal thread screen starts.
 */
class BubbleThreadActivity : ThreadActivity()
