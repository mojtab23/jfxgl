/*************************************************************************
 * Copyright (C) 2017, Jeffrey W. Martin "Cuchaz"
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License Version 2 with
 * the classpath exception, as published by the Free Software Foundation.
 * 
 * See LICENSE.txt in the project root folder for the full license.
 *************************************************************************/
package cuchaz.jfxgl.glass;

import java.nio.IntBuffer;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWWindowSizeCallbackI;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;

import com.sun.glass.events.WindowEvent;
import com.sun.glass.ui.Cursor;
import com.sun.glass.ui.Pixels;
import com.sun.glass.ui.Screen;
import com.sun.glass.ui.View;
import com.sun.glass.ui.Window;
import com.sun.javafx.application.PlatformImpl;

import cuchaz.jfxgl.CalledByEventsThread;
import cuchaz.jfxgl.CalledByMainThread;
import cuchaz.jfxgl.GLState;
import cuchaz.jfxgl.InAppGLContext;
import cuchaz.jfxgl.InJavaFXGLContext;
import cuchaz.jfxgl.prism.JFXGLContext;
import cuchaz.jfxgl.prism.JFXGLContexts;
import cuchaz.jfxgl.prism.OffscreenBuffer;

public class JFXGLWindow extends Window {
	
	public static JFXGLWindow mainWindow = null;
	
	private JFXGLContext context = null;
	
	private JFXGLView view = null;
	private GLFWWindowSizeCallbackI windowSizeCallback = null;
	private GLFWWindowSizeCallbackI existingWindowSizeCallback = null;
	
	private int width = 0;
	private int height = 0;
	private OffscreenBuffer buf = null;
	private boolean fboDirty = true;
	private GLState glstate = new GLState(
		GLState.Blend, GLState.BlendFunc, GLState.ShaderProgram,
		GLState.ActiveTexture, GLState.Texture2D[0],
		GLState.VertexArray, GLState.ArrayBuffer,
		GLState.Viewport
	);
	
	protected JFXGLWindow(Window owner, Screen screen, int styleMask) {
		super(owner, screen, styleMask);
		
		// only ever create one window
		if (mainWindow != null) {
			throw new IllegalStateException("can't create more than one window");
		}
		mainWindow = this;
		
		// get our context
		context = JFXGLContexts.app;
		
		// NOTE: always keep a strong reference to callbacks, or they get garbage collected
		windowSizeCallback = (long hwndAgain, int width, int height) -> {
			
			// save our own copy of the size for framebuffer sizing
			if (width != this.width || height != this.height) {
				this.width = width;
				this.height = height;
				this.fboDirty = true;
			}
			
			// NOTE: GLFW events called on main thread, so relay to events thread
			PlatformImpl.runLater(() -> {
				notifyResize(WindowEvent.RESIZE, width, height);
				if (view != null) {
					view.notifyResize(this.width, this.height);
				}
			});
			
			// call the existing callback, if any
			if (existingWindowSizeCallback != null) {
				existingWindowSizeCallback.invoke(hwndAgain, width, height);
			}
		};
		existingWindowSizeCallback = GLFW.glfwSetWindowSizeCallback(context.hwnd, windowSizeCallback);
		
		// NOTE: don't bother trying to read the window size here
		// nothing cares if we try anyway
	}
	
	@Override
	@CalledByEventsThread
	protected long _createWindow(long ownerhwnd, long screenhwnd, int mask) {
		
		// NOTE: this is called by the super constructor, so it happens BEFORE field initialization!!
		// meaning, we can't assign fields here, because they will get overwritten later!
		
		// don't actually create a window here
		// use the one that was already created by the main thread
		return JFXGLContexts.app.hwnd;
	}

	@Override
	protected long _createChildWindow(long parent) {
		throw new UnsupportedOperationException();
	}

	@Override
	protected boolean _close(long hwnd) {
		
		// NOTE: don't call notifyClose() here
		
		// and don't actually destroy the GLFW window either
		// the main thread will take care of that
		notifyDestroy();
		
		return false; // return value apparently ignored
	}

	@Override
	@CalledByEventsThread
	protected boolean _setView(long hwnd, View view) {
		
		this.view = (JFXGLView)view;
		
		// tell JavaFX to update the view size
		if (this.view != null) {
			
			// get the initial window size
			try (MemoryStack m = MemoryStack.stackPush()) {
				IntBuffer widthBuf = m.callocInt(1);
				IntBuffer heightBuf = m.callocInt(1);
				GLFW.glfwGetWindowSize(context.hwnd, widthBuf, heightBuf);
				width = widthBuf.get(0);
				height = heightBuf.get(0);
			}
			
			// if we notify resize now, JavaFX just ignores it
			// so put it on the event queue
			PlatformImpl.runLater(() -> {
				notifyResize(WindowEvent.RESIZE, width, height);
				this.view.notifyResize(width, height);
			});
		}
		
		return true;
	}
	
	@CalledByMainThread
	@InJavaFXGLContext
	public void renderBegin() {
		
		// do we need to resize the framebuffer?
		if (buf == null) {
			buf = new OffscreenBuffer(context, width, height);
		}
		if (fboDirty) {
			fboDirty = false;
			buf.resize(width, height);
		}
	}
	
	@CalledByMainThread
	@InJavaFXGLContext
	public void renderEnd() {
	
		// nothing to do
	}
	
	@CalledByMainThread
	public int getFBOId() {
		if (buf != null) {
			return buf.getFboId();
		}
		return 0;
	}
	
	@CalledByMainThread
	@InAppGLContext
	public void renderFramebuf() {
		if (buf != null) {
			
			glstate.backup();
			
			GL11.glEnable(GL11.GL_BLEND);
			GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
			GL11.glViewport(0, 0, width, height);
			
			// composite our framebuffer onto the main framebuffer
			buf.render();
			
			glstate.restore();
		}
	}
	
	public OffscreenBuffer getBuffer() {
		return buf;
	}
	
	// override these to disable the event thread check,
	// just use the size in this class
	// who cares if we race on window size?
	// the framebuffer has its own size
	
	@Override
	public int getWidth() {
		return width;
	}
	
	@Override
	public int getHeight() {
		return height;
	}

	@Override
	protected boolean _setMenubar(long hwnd, long menubarhwnd) {
		throw new UnsupportedOperationException();
	}

	@Override
	protected boolean _minimize(long hwnd, boolean minimize) {
		// don't let JavaFX control the window
		return false;
	}

	@Override
	protected boolean _maximize(long hwnd, boolean maximize, boolean wasMaximized) {
		// don't let JavaFX control the window
		return false;
	}

	@Override
	protected int _getEmbeddedX(long hwnd) {
		throw new UnsupportedOperationException();
	}

	@Override
	protected int _getEmbeddedY(long hwnd) {
		throw new UnsupportedOperationException();
	}

	@Override
	protected void _setBounds(long hwnd, int x, int y, boolean xSet, boolean ySet, int w, int h, int cw, int ch, float xGravity, float yGravity) {
		
		// don't let JavaFX control the window size
		
		/* but if we wanted to...
		
		if (w > 0) {
			// window width surpass window content width (cw)
			width = w;
		} else if (cw > 0) {
			// content width changed
			width = cw;
		}

		if (h > 0) {
			// window height surpass window content height(ch)
			height = h;
		} else if (cw > 0) {
			// content height changed
			height = ch;
		}
		
		if (!xSet) {
			x = getX();
		}
		if (!ySet) {
			y = getY();
		}
		if (maxWidth >= 0) {
			width = Math.min(width, maxWidth);
		}
		if (maxHeight >= 0) {
			height = Math.min(height, maxHeight);
		}
		width = Math.max(width, minWidth);
		height = Math.max(height, minHeight);

		GLFW.glfwSetWindowSize(hwnd, width, height);
		GLFW.glfwSetWindowPos(hwnd, x, y);
		*/
	}

	protected boolean _setVisible(long hwnd, boolean visible) {
		// don't let JavaFX control the window
		return visible;
	}

	@Override
	protected boolean _setResizable(long hwnd, boolean resizable) {
		// don't let JavaFX control the window
		return false;
	}
	
	public void handleGLFWFocus(boolean isFocused) {
		this.notifyFocus(isFocused ? WindowEvent.FOCUS_GAINED : WindowEvent.FOCUS_LOST);
	}
	
	@Override
	protected boolean _requestFocus(long hwnd, int event) {
		GLFW.glfwFocusWindow(hwnd);
		return true;
	}

	@Override
	protected void _setFocusable(long hwnd, boolean isFocusable) {
		// don't let JavaFX control the window
	}

	@Override
	protected boolean _grabFocus(long hwnd) {
		throw new UnsupportedOperationException();
	}

	@Override
	protected void _ungrabFocus(long hwnd) {
		throw new UnsupportedOperationException();
	}

	@Override
	protected boolean _setTitle(long hwnd, String title) {
		// don't let JavaFX control the window
		return false;
	}

	@Override
	protected void _setLevel(long hwnd, int level) {
		// don't let JavaFX control the window
	}

	@Override
	protected void _setAlpha(long hwnd, float alpha) {
		// can't change window alpha, just ignore
	}

	@Override
	protected boolean _setBackground(long hwnd, float r, float g, float b) {
		// don't let JavaFX control the window
		return false;
	}

	@Override
	protected void _setEnabled(long hwnd, boolean enabled) {
		// don't let JavaFX control the window
	}

	@Override
	protected boolean _setMinimumSize(long hwnd, int width, int height) {
		// don't let JavaFX control the window
		return false;
	}

	@Override
	protected boolean _setMaximumSize(long hwnd, int width, int height) {
		// don't let JavaFX control the window
		return false;
	}

	@Override
	protected void _setIcon(long hwnd, Pixels pixels) {
		// don't let JavaFX control the window
	}

	long hcursor = 0;
	
	@Override
	protected void _setCursor(long hwnd, Cursor cursor) {
		
		// TODO: add flag to allow JavaFX cursors?
		
		// cleanup the old cursor if needed
		if (hcursor != 0) {
			GLFW.glfwDestroyCursor(hcursor);
			hcursor = 0;
		}
		
		hcursor = GLFW.glfwCreateStandardCursor(translateCursor(cursor));
		GLFW.glfwSetCursor(hwnd, hcursor);
	}
	
	private int translateCursor(Cursor cursor) {
		switch (cursor.getType()) {
			case Cursor.CURSOR_NONE: return GLFW.GLFW_ARROW_CURSOR;
		    case Cursor.CURSOR_CUSTOM: return GLFW.GLFW_ARROW_CURSOR;
		    case Cursor.CURSOR_DEFAULT: return GLFW.GLFW_ARROW_CURSOR;
		    case Cursor.CURSOR_TEXT: return GLFW.GLFW_IBEAM_CURSOR;
		    case Cursor.CURSOR_CROSSHAIR: return GLFW.GLFW_CROSSHAIR_CURSOR;
		    case Cursor.CURSOR_CLOSED_HAND: return GLFW.GLFW_HAND_CURSOR;
		    case Cursor.CURSOR_OPEN_HAND: return GLFW.GLFW_HAND_CURSOR;
		    case Cursor.CURSOR_POINTING_HAND: return GLFW.GLFW_HAND_CURSOR;
		    case Cursor.CURSOR_RESIZE_LEFT: return GLFW.GLFW_HRESIZE_CURSOR;
		    case Cursor.CURSOR_RESIZE_RIGHT: return GLFW.GLFW_HRESIZE_CURSOR;
		    case Cursor.CURSOR_RESIZE_UP: return GLFW.GLFW_VRESIZE_CURSOR;
		    case Cursor.CURSOR_RESIZE_DOWN: return GLFW.GLFW_VRESIZE_CURSOR;
		    case Cursor.CURSOR_RESIZE_LEFTRIGHT: return GLFW.GLFW_HRESIZE_CURSOR;
		    case Cursor.CURSOR_RESIZE_UPDOWN: return GLFW.GLFW_VRESIZE_CURSOR;
		    case Cursor.CURSOR_DISAPPEAR: return GLFW.GLFW_ARROW_CURSOR;
		    case Cursor.CURSOR_WAIT: return GLFW.GLFW_ARROW_CURSOR;
		    case Cursor.CURSOR_RESIZE_SOUTHWEST: return GLFW.GLFW_ARROW_CURSOR;
		    case Cursor.CURSOR_RESIZE_SOUTHEAST: return GLFW.GLFW_ARROW_CURSOR;
		    case Cursor.CURSOR_RESIZE_NORTHWEST: return GLFW.GLFW_ARROW_CURSOR;
		    case Cursor.CURSOR_RESIZE_NORTHEAST: return GLFW.GLFW_ARROW_CURSOR;
		    case Cursor.CURSOR_MOVE: return GLFW.GLFW_ARROW_CURSOR;
		    default: return GLFW.GLFW_ARROW_CURSOR;
		}
	}

	@Override
	protected void _toFront(long hwnd) {
		// don't let JavaFX control the window
	}

	@Override
	protected void _toBack(long hwnd) {
		// don't let JavaFX control the window
	}

	@Override
	protected void _enterModal(long hwnd) {
		// don't let JavaFX control the window
	}

	@Override
	protected void _enterModalWithWindow(long dialog, long window) {
		// don't let JavaFX control the window
	}

	@Override
	protected void _exitModal(long hwnd) {
		// don't let JavaFX control the window
	}

	@Override
	protected void _requestInput(long hwnd, String text, int type, double width, double height, double Mxx, double Mxy,
			double Mxz, double Mxt, double Myx, double Myy, double Myz, double Myt, double Mzx, double Mzy, double Mzz,
			double Mzt) {
		throw new UnsupportedOperationException();
	}

	@Override
	protected void _releaseInput(long hwnd) {
		throw new UnsupportedOperationException();
	}
}
