import {
  ArrowLeft, Bot, Boxes, Check, ChevronDown, ChevronRight, CircleAlert, CircleCheck, Eye, File,
  FileCode2, FileDiff, FilePlus2, Folder, FolderOpen, FolderPlus, FolderGit2,
  GitCompareArrows, History, KeyRound, LoaderCircle, LockKeyhole, LogOut, MessageSquare,
  MoreHorizontal, PanelRight, Pencil, Play, Plus, RefreshCw, Save, Send, Settings, Settings2,
  ShieldCheck, Sun, Trash2, Workflow, X
} from 'lucide';

const registry = {
  ArrowLeft, Bot, Boxes, Check, ChevronDown, ChevronRight, CircleAlert, CircleCheck, Eye, File,
  FileCode2, FileDiff, FilePlus2, Folder, FolderOpen, FolderPlus, FolderGit2,
  GitCompareArrows, History, KeyRound, LoaderCircle, LockKeyhole, LogOut, MessageSquare,
  MoreHorizontal, PanelRight, Pencil, Play, Plus, RefreshCw, Save, Send, Settings, Settings2,
  ShieldCheck, Sun, Trash2, Workflow, X
};

export function renderIcon(name, attributes = {}) {
  const icon = registry[name];
  if (!icon) return document.createTextNode('');
  const holder = document.createElement('span');
  holder.innerHTML = icon.toSvg({ width: 16, height: 16, 'stroke-width': 1.8, ...attributes });
  return holder.firstElementChild;
}

export function hydrateIcons(scope = document) {
  scope.querySelectorAll?.('[data-lucide]').forEach(element => {
    const icon = renderIcon(element.dataset.lucide, {
      width: element.dataset.iconSize || 18,
      height: element.dataset.iconSize || 18,
      'aria-hidden': 'true'
    });
    if (icon instanceof Element) element.replaceWith(icon);
  });
}
