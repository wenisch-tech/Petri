import {
  ArrowLeft, Bot, Boxes, Check, ChevronDown, ChevronRight, CircleAlert, CircleCheck, Eye, File,
  CloudDownload, CloudUpload,
  Database, FileArchive, FileBraces, FileCode2, FileCog, FileDiff, FileImage, FileJson,
  FilePlus2, FileSpreadsheet, FileTerminal, FileText, FileType2, Folder, FolderOpen, FolderPlus, FolderGit2,
  GitBranch, GitBranchPlus, GitCommitHorizontal, GitCompare, GitCompareArrows, GitPullRequestArrow,
  History, KeyRound, LoaderCircle, LockKeyhole, LogOut, MessageSquare,
  MoreHorizontal, PanelRight, Pencil, Play, Plus, RefreshCw, Save, Send, Settings, Settings2,
  ShieldCheck, Sun, Trash2, Workflow, X, createElement as createLucideElement
} from 'lucide';

const registry = {
  ArrowLeft, Bot, Boxes, Check, ChevronDown, ChevronRight, CircleAlert, CircleCheck, Database, Eye, File,
  CloudDownload, CloudUpload,
  FileArchive, FileBraces, FileCode2, FileCog, FileDiff, FileImage, FileJson, FilePlus2,
  FileSpreadsheet, FileTerminal, FileText, FileType2, Folder, FolderOpen, FolderPlus, FolderGit2,
  GitBranch, GitBranchPlus, GitCommitHorizontal, GitCompare, GitCompareArrows, GitPullRequestArrow,
  History, KeyRound, LoaderCircle, LockKeyhole, LogOut, MessageSquare,
  MoreHorizontal, PanelRight, Pencil, Play, Plus, RefreshCw, Save, Send, Settings, Settings2,
  ShieldCheck, Sun, Trash2, Workflow, X
};

export function renderIcon(name, attributes = {}) {
  const icon = registry[name];
  if (!icon) return document.createTextNode('');
  return createLucideElement(icon, {
    width: 16,
    height: 16,
    'stroke-width': 1.8,
    'aria-hidden': 'true',
    ...attributes
  });
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
