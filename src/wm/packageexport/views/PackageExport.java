package wm.packageexport.views;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;

/**
 * A custom Eclipse View that actively monitors package selection events from Software AG's
 * Package Navigator and provides a button to sequentially trigger batch exports.
 */
public class PackageExport extends ViewPart {

	private Text logText;
	private Button runBtn;
	private ISelectionListener selectionListener;

	private IStructuredSelection lastSelection;
	private IWorkbenchPart lastPart;

	private final Map<Class<?>, Boolean> isPackageNodeCache = new HashMap<>();

	@Override
	public void createPartControl(Composite parent) {
		GridLayout layout = new GridLayout(1, false);
		layout.marginHeight = 5;
		layout.marginWidth = 5;
		parent.setLayout(layout);

		logText = new Text(parent, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL | SWT.WRAP);
		logText.setEditable(false);
		logText.setText("Select one or more packages in Software AG's Package Navigator to begin...");
		logText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

		runBtn = new Button(parent, SWT.PUSH);
		runBtn.setText("Export Selected Packages");
		runBtn.setToolTipText("Sequentially triggers 'Export from Server' and auto-dismisses dialogs for all selected packages");
		runBtn.setEnabled(false); // Disabled initially
		runBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		runBtn.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				runBatchCommand();
			}
		});

		registerSelectionListener();
	}

	private void registerSelectionListener() {
		selectionListener = new ISelectionListener() {
			@Override
			public void selectionChanged(IWorkbenchPart part, ISelection selection) {
				handleSelectionChanged(part, selection);
			}
		};
		getSite().getPage().addSelectionListener(selectionListener);
	}

	private void handleSelectionChanged(IWorkbenchPart part, ISelection selection) {
		if (part == PackageExport.this) {
			return;
		}

		final StringBuilder sb = new StringBuilder();
		sb.append("Source View Title: ").append(part.getTitle()).append("\n");
		sb.append("Source View ID:    ").append(part.getSite().getId()).append("\n");
		sb.append("------------------------------------------------------------\n");

		boolean allArePackages = false;

		if (selection instanceof IStructuredSelection) {
			IStructuredSelection ss = (IStructuredSelection) selection;
			lastSelection = ss;
			lastPart = part;

			if (ss.isEmpty()) {
				sb.append("Selection is empty.");
			} else {
				allArePackages = true;
				sb.append("Selected item count: ").append(ss.size()).append("\n\n");
				
				// PERFORMANCE OPTIMIZATION: Only reflectively scan public getters for selections <= 5.
				// For large selections (like 20+), skip deep inspection to prevent UI thread halting.
				boolean showDetails = ss.size() <= 5;
				if (!showDetails) {
					sb.append("(Detailed property reflection is disabled for large selections to maximize performance)\n\n");
				}

				int index = 1;
				for (Object element : ss.toList()) {
					boolean isPkg = isPackageNodeReflectively(element);
					if (!isPkg) {
						allArePackages = false;
					}

					sb.append("[").append(index++).append("] ");
					if (element != null) {
						if (showDetails) {
							Class<?> clazz = element.getClass();
							sb.append("Element details:\n");
							sb.append("  - Runtime Class: ").append(clazz.getName()).append("\n");
							sb.append("  - isPackageNode: ").append(isPkg ? "TRUE" : "FALSE").append("\n");
							sb.append("  - toString():    ").append(element.toString()).append("\n");

							// Perform reflective inspection on elements to extract relevant data
							sb.append("  - Reflected properties:\n");
							try {
								java.lang.reflect.Method[] methods = clazz.getMethods();
								boolean foundProperties = false;
								for (java.lang.reflect.Method method : methods) {
									String methodName = method.getName();
									if ((methodName.startsWith("get") || methodName.startsWith("is"))
											&& method.getParameterCount() == 0
											&& !methodName.equals("getClass")
											&& !methodName.equals("hashCode")
											&& !methodName.equals("toString")) {

										Class<?> returnType = method.getReturnType();
										if (returnType == String.class || returnType.isPrimitive()
												|| Number.class.isAssignableFrom(returnType)) {
											try {
												Object val = method.invoke(element);
												if (val != null) {
													sb.append("    * ").append(methodName).append("(): ").append(val).append("\n");
													foundProperties = true;
												}
											} catch (Exception e) {
												// Skip individual invocation failures
											}
										}
									}
								}
								if (!foundProperties) {
									sb.append("    (No accessible zero-parameter string/primitive properties found)\n");
								}
							} catch (Exception e) {
								sb.append("    (Reflection failed: ").append(e.getMessage()).append(")\n");
							}
							sb.append("\n");
						} else {
							sb.append(element.toString()).append(" (Class: ").append(element.getClass().getSimpleName()).append(")\n");
						}
					} else {
						sb.append("null\n");
						if (showDetails) {
							sb.append("\n");
						}
					}
				}
			}
		} else if (selection != null) {
			sb.append("Selection Type: ").append(selection.getClass().getName()).append("\n");
			sb.append("Value:          ").append(selection.toString()).append("\n");
		} else {
			sb.append("No active selection.");
		}

		final String logResult = sb.toString();
		final boolean enableBtn = allArePackages;

		try {
			PlatformUI.getWorkbench().getDisplay().asyncExec(new Runnable() {
				@Override
				public void run() {
					if (logText != null && !logText.isDisposed()) {
						logText.setText(logResult);
					}
					if (runBtn != null && !runBtn.isDisposed()) {
						runBtn.setEnabled(enableBtn);
					}
				}
			});
		} catch (Exception e) {
			// Catch any early-disposal or platform context errors during shutdown
		}
	}

	private boolean isPackageNodeReflectively(Object element) {
		if (element == null) return false;
		Class<?> clazz = element.getClass();
		
		Boolean cached = isPackageNodeCache.get(clazz);
		if (cached != null) {
			return cached;
		}

		boolean result = false;
		
		try {
			java.lang.reflect.Method method = clazz.getMethod("isPackageNode");
			Object objResult = method.invoke(element);
			if (objResult instanceof Boolean) {
				result = (Boolean) objResult;
			}
		} catch (Exception e) {
			// Skip and fall back
		}

		if (!result) {
			String className = clazz.getName();
			if (className.contains("PackageNode") || className.contains("PkgNode")) {
				result = true;
			}
		}

		isPackageNodeCache.put(clazz, result);
		return result;
	}

	private void runBatchCommand() {
		final String targetMenuLabel = "Export from Server";

		if (lastSelection == null || lastSelection.isEmpty()) {
			logText.setText("Error: Selection is empty.\n\nPlease select multiple packages in the Package Navigator view first, and then click this button.");
			return;
		}

		final IWorkbenchPart targetPart = lastPart;
		if (targetPart == null) {
			logText.setText("Error: Source view not found. Please click a package in the Package Navigator first.");
			return;
		}

		final List<?> elementsToProcess = lastSelection.toList();
		logText.setText("Starting Automated Batch Export:\n");
		logText.append("Target Menu Item: " + targetMenuLabel + "\n");
		logText.append("Processing:       " + elementsToProcess.size() + " items.\n");
		logText.append("Status:           Asynchronous dialog dismissal threads are ACTIVE.\n");
		logText.append("------------------------------------------------------------\n");

		final org.eclipse.swt.widgets.Listener dialogAutomator = new org.eclipse.swt.widgets.Listener() {
			@Override
			public void handleEvent(org.eclipse.swt.widgets.Event event) {
				if (event.widget instanceof org.eclipse.swt.widgets.Shell) {
					final org.eclipse.swt.widgets.Shell shell = (org.eclipse.swt.widgets.Shell) event.widget;
					
					// Run asynchronously to allow the dialog shell to fully instantiate and build its controls
					shell.getDisplay().asyncExec(new Runnable() {
						@Override
						public void run() {
							if (shell.isDisposed()) return;
							String title = shell.getText();
							
							if (title != null && (title.toLowerCase().contains("export") 
									|| title.toLowerCase().contains("folder") 
									|| title.toLowerCase().contains("select") 
									|| title.toLowerCase().contains("destination")
									|| title.toLowerCase().contains("server")
									|| title.toLowerCase().contains("confirm"))) {
								
								org.eclipse.swt.widgets.Button confirmBtn = findButtonByText(shell, new String[] { "Finish", "OK", "Select", "Save", "Yes" });
								if (confirmBtn != null) {
									appendLog("  * Intercepted Eclipse Dialog: '" + title + "' -> Auto-clicking button: '" + confirmBtn.getText() + "'\n");
									
									// Simulate a click event on the button
									org.eclipse.swt.widgets.Event clickEvent = new org.eclipse.swt.widgets.Event();
									clickEvent.type = SWT.Selection;
									confirmBtn.notifyListeners(SWT.Selection, clickEvent);
								} else {
									// If we can't find a matching button, list all found buttons to help diagnostics
									List<String> foundButtons = new ArrayList<>();
									listAllButtons(shell, foundButtons);
									appendLog("  * Intercepted Eclipse Dialog: '" + title + "', but no matching confirmation button found. Available buttons: " + foundButtons + "\n");
								}
							}
						}
					});
				}
			}
		};

		// Warning: the parallel deamon thread may not work!
		// Run in a worker thread so we don't freeze the Eclipse IDE UI thread during sleep/execution
		Thread thread = new Thread(new Runnable() {
			@Override
			public void run() {
				// 1. Register the dynamic dialog-interceptor filter on the Display (for Eclipse internal JFace shells)
				PlatformUI.getWorkbench().getDisplay().syncExec(new Runnable() {
					@Override
					public void run() {
						PlatformUI.getWorkbench().getDisplay().addFilter(SWT.Show, dialogAutomator);
					}
				});

				try {
					for (int i = 0; i < elementsToProcess.size(); i++) {
						final Object element = elementsToProcess.get(i);
						final int index = i + 1;

						appendLog("Item [" + index + "/" + elementsToProcess.size() + "]: " + element.toString() + "\n");

						// 2. Start an independent background parallel daemon BEFORE syncExec to auto-accept native dialogs.
						final String os = System.getProperty("os.name").toLowerCase();
						if (os.contains("mac") || os.contains("win")) {
							Thread keystrokeThread = new Thread(new Runnable() {
								@Override
								public void run() {
									try {
										// Wait 1.0 second for the UI thread to mount and display the native dialog
										Thread.sleep(1000);
										if (os.contains("mac")) {
											appendLog("  * macOS parallel daemon: Dispatching AppleScript to auto-accept native file/folder picker...\n");
											Runtime.getRuntime().exec(new String[] {
												"osascript",
												"-e",
												"tell application \"System Events\" to keystroke return"
											});
										} else if (os.contains("win")) {
											appendLog("  * Windows parallel daemon: Dispatching PowerShell to auto-accept native file/folder picker...\n");
											Runtime.getRuntime().exec(new String[] {
												"powershell",
												"-Command",
												"(New-Object -ComObject WScript.Shell).SendKeys('{ENTER}')"
											});
										}
									} catch (Exception e) {
										appendLog("  * Parallel daemon keystroke dispatch failed: " + e.getMessage() + "\n");
									}
								}
							});
							keystrokeThread.setDaemon(true);
							keystrokeThread.start();
						}

						// 3. NOW execute the blocking SWT operations synchronously on the UI thread
						PlatformUI.getWorkbench().getDisplay().syncExec(new Runnable() {
							@Override
							public void run() {
								try {
									// 1. Programmatically update selection in the Software AG Package Navigator view
									if (targetPart.getSite() != null && targetPart.getSite().getSelectionProvider() != null) {
										ISelectionProvider prov = targetPart.getSite().getSelectionProvider();
										prov.setSelection(new StructuredSelection(element));
									}

									// 2. Allow Eclipse selection changes to propagate
									while (PlatformUI.getWorkbench().getDisplay().readAndDispatch()) {
										// Process event queue
									}

									// 3. Trigger action via reflective Context Menu Simulation
									org.eclipse.swt.widgets.Control treeControl = findTreeControlReflectively(targetPart);
									if (treeControl != null) {
										boolean clicked = scanAndTriggerMenu(treeControl.getMenu(), targetMenuLabel);
										if (clicked) {
											appendLog("  * Programmatically clicked context menu: '" + targetMenuLabel + "'\n");
										} else {
											// Fallback: search for a simpler keyword "Export" if "Export from Server" fails
											appendLog("  * 'Export from Server' not found, searching for general 'Export'...\n");
											boolean fallbackClicked = scanAndTriggerMenu(treeControl.getMenu(), "Export");
											if (fallbackClicked) {
												appendLog("  * Programmatically clicked context menu: 'Export'\n");
											} else {
												appendLog("  * ERROR: Context menu option containing '" + targetMenuLabel + "' or 'Export' was not found.\n");
											}
										}
									} else {
										appendLog("  * ERROR: Could not locate active Tree/Table control inside Package Navigator.\n");
									}

									// 4. Force UI dispatching so export wizards/progress dialogs can render and trigger our automator
									while (PlatformUI.getWorkbench().getDisplay().readAndDispatch()) {
										// Process event queue
									}
								} catch (Exception e) {
									appendLog("  * FAILED: " + e.getMessage() + "\n");
								}
							}
						});

						// Pause for 1.8 seconds between items to let each Integration Server export complete and dialogs handle cleanly
						Thread.sleep(1800);
					}
					appendLog("\n=== Automated Batch Export Process Completed! ===\n");
				} catch (InterruptedException e) {
					appendLog("\n=== Batch process was interrupted ===\n");
				} finally {
					// 2. ALWAYS clean up and unregister the dialog-interceptor filter when finished
					PlatformUI.getWorkbench().getDisplay().syncExec(new Runnable() {
						@Override
						public void run() {
							PlatformUI.getWorkbench().getDisplay().removeFilter(SWT.Show, dialogAutomator);
						}
					});
					appendLog("Status:           Automatic Dialog Dismissal Filter is now DEACTIVATED.\n");
				}
			}
		});
		thread.start();
	}

	private boolean scanAndTriggerMenu(org.eclipse.swt.widgets.Menu menu, String label) {
		if (menu == null) return false;

		// Force population of dynamic menu contributions
		org.eclipse.swt.widgets.Event showEvent = new org.eclipse.swt.widgets.Event();
		showEvent.type = SWT.Show;
		menu.notifyListeners(SWT.Show, showEvent);

		org.eclipse.swt.widgets.MenuItem[] items = menu.getItems();
		for (org.eclipse.swt.widgets.MenuItem item : items) {
			String text = item.getText();
			if (text != null) {
				// Strip out hotkey markers like '&' (e.g. "&Export" -> "Export")
				String cleanText = text.replace("&", "");
				if (cleanText.toLowerCase().contains(label.toLowerCase())) {
					org.eclipse.swt.widgets.Event selectionEvent = new org.eclipse.swt.widgets.Event();
					selectionEvent.type = SWT.Selection;
					item.notifyListeners(SWT.Selection, selectionEvent);

					org.eclipse.swt.widgets.Event hideEvent = new org.eclipse.swt.widgets.Event();
					hideEvent.type = SWT.Hide;
					menu.notifyListeners(SWT.Hide, hideEvent);
					return true;
				}
			}

			if (item.getMenu() != null) {
				if (scanAndTriggerMenu(item.getMenu(), label)) {
					org.eclipse.swt.widgets.Event hideEvent = new org.eclipse.swt.widgets.Event();
					hideEvent.type = SWT.Hide;
					menu.notifyListeners(SWT.Hide, hideEvent);
					return true;
				}
			}
		}

		org.eclipse.swt.widgets.Event hideEvent = new org.eclipse.swt.widgets.Event();
		hideEvent.type = SWT.Hide;
		menu.notifyListeners(SWT.Hide, hideEvent);
		return false;
	}

	private org.eclipse.swt.widgets.Control findTreeControlReflectively(Object obj) {
		if (obj == null) return null;
		Class<?> clazz = obj.getClass();
		while (clazz != null) {
			for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
				field.setAccessible(true);
				try {
					Object val = field.get(obj);
					if (val instanceof org.eclipse.jface.viewers.Viewer) {
						org.eclipse.swt.widgets.Control ctrl = ((org.eclipse.jface.viewers.Viewer) val).getControl();
						org.eclipse.swt.widgets.Control tree = findTreeOrTableControl(ctrl);
						if (tree != null) return tree;
					}
					if (val instanceof org.eclipse.swt.widgets.Control) {
						org.eclipse.swt.widgets.Control tree = findTreeOrTableControl((org.eclipse.swt.widgets.Control) val);
						if (tree != null) return tree;
					}
				} catch (Exception e) {
					// Skip field access errors
				}
			}
			clazz = clazz.getSuperclass();
		}
		return null;
	}

	private org.eclipse.swt.widgets.Control findTreeOrTableControl(org.eclipse.swt.widgets.Control control) {
		if (control == null) return null;
		if (control instanceof org.eclipse.swt.widgets.Tree || control instanceof org.eclipse.swt.widgets.Table) {
			return control;
		}
		if (control instanceof org.eclipse.swt.widgets.Composite) {
			for (org.eclipse.swt.widgets.Control child : ((org.eclipse.swt.widgets.Composite) control).getChildren()) {
				org.eclipse.swt.widgets.Control found = findTreeOrTableControl(child);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	private org.eclipse.swt.widgets.Button findButtonByText(Control control, String[] labels) {
		if (control == null) return null;
		if (control instanceof org.eclipse.swt.widgets.Button) {
			org.eclipse.swt.widgets.Button btn = (org.eclipse.swt.widgets.Button) control;
			String text = btn.getText();
			if (text != null) {
				String cleanText = text.replace("&", "").toLowerCase().trim();
				for (String label : labels) {
					if (cleanText.equalsIgnoreCase(label.toLowerCase()) || cleanText.contains(label.toLowerCase())) {
						return btn;
					}
				}
			}
		}
		if (control instanceof Composite) {
			for (Control child : ((Composite) control).getChildren()) {
				org.eclipse.swt.widgets.Button btn = findButtonByText(child, labels);
				if (btn != null) {
					return btn;
				}
			}
		}
		return null;
	}

	private void listAllButtons(Control control, List<String> list) {
		if (control == null) return;
		if (control instanceof org.eclipse.swt.widgets.Button) {
			String text = ((org.eclipse.swt.widgets.Button) control).getText();
			if (text != null && !text.isEmpty()) {
				list.add(text.replace("&", ""));
			}
		}
		if (control instanceof Composite) {
			for (Control child : ((Composite) control).getChildren()) {
				listAllButtons(child, list);
			}
		}
	}

	private void appendLog(final String text) {
		if (logText != null && !logText.isDisposed()) {
			logText.getDisplay().asyncExec(new Runnable() {
				@Override
				public void run() {
					if (logText != null && !logText.isDisposed()) {
						logText.append(text);
					}
				}
			});
		}
	}

	@Override
	public void setFocus() {
		if (logText != null && !logText.isDisposed()) {
			logText.setFocus();
		}
	}

	@Override
	public void dispose() {
		// Clean up the selection listener to prevent memory leaks
		if (selectionListener != null && getSite() != null && getSite().getPage() != null) {
			getSite().getPage().removeSelectionListener(selectionListener);
		}
		super.dispose();
	}
}
