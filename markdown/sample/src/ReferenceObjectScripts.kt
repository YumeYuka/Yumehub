package moe.yumeyuka.markdown.sample

import org.json.JSONArray

/** 参考侧只读 DOM 的真实文字、对象与引擎布局；未测绘制边界保持 null。 */
internal object ReferenceObjectScripts {
    fun measure(fixture: AcceptanceFixture, dark: Boolean): String =
        """
      const objectState=(function() {
        const root=document.getElementById('content');
        const catalogue=${JSONArray(fixture.objectNodeIds)};
        const geometryIds=new Set(${JSONArray(fixture.geometryNodeIds)});
        const input=window.__fixtureObjectInputs || Object.create(null);
        const elements=new Map(Array.from(root.querySelectorAll('[data-fixture-object-id]')).map(el=>[el.getAttribute('data-fixture-object-id'),el]));
        const measured=[],excluded=[];
        function idOf(element) {return element.getAttribute('data-fixture-id') || element.id || null;}
        function ownerOf(node) {
          let element=node.nodeType===Node.TEXT_NODE ? node.parentElement : node.parentElement;
          while(element && element!==root) {if(geometryIds.has(idOf(element)))return element;element=element.parentElement;}
          return root;
        }
        function rect(element) {const r=element.getBoundingClientRect();return [r.left,r.top,r.right,r.bottom];}
        function union(values) {
          const valid=values.filter(r=>r && r[2]>r[0] && r[3]>r[1]);
          return valid.length ? [Math.min(...valid.map(r=>r[0])),Math.min(...valid.map(r=>r[1])),Math.max(...valid.map(r=>r[2])),Math.max(...valid.map(r=>r[3]))] : null;
        }
        function block(element) {
          if(element.nodeType!==Node.ELEMENT_NODE)return false;
          const spec=input[element.getAttribute('data-fixture-object-id')];
          if(spec && (spec.kind==='mermaid' || spec.kind==='math' && spec.semantics.display))return true;
          return ['block','flex','grid','table','list-item'].includes(getComputedStyle(element).display);
        }
        function flowKey(node) {
          const owner=ownerOf(node);let child=node;
          while(child.parentNode && child.parentNode!==owner)child=child.parentNode;
          let index=0;
          for(let before=child.previousSibling;before;before=before.previousSibling)if(block(before))index++;
          return (owner===root ? 'root' : idOf(owner))+'::flow:'+index;
        }
        function expectedHidden(element) {
          for(let node=element;node && node!==root;node=node.parentElement) {
            const href=node.getAttribute('href') || node.getAttribute('src') || '';
            if(node.hasAttribute('hidden') || node.style.display==='none' || node.style.visibility==='hidden')return true;
            if(${dark} && href.endsWith('#gh-light-mode-only') || !${dark} && href.endsWith('#gh-dark-mode-only'))return true;
          }
          return false;
        }
        function closedDetails(element) {
          for(let node=element.parentElement;node && node!==root;node=node.parentElement) {
            if(node.tagName==='DETAILS' && !node.open) {
              const summary=Array.from(node.children).find(child=>child.tagName==='SUMMARY');
              if(!summary || !summary.contains(element))return true;
            }
          }
          return false;
        }
        function laidOut(element) {
          const style=getComputedStyle(element);return style.display!=='none' && style.visibility!=='hidden' && element.getClientRects().length>0;
        }
        function ordinaryText(element) {
          const textUnit=/^(P|H[1-6]|PRE|TD|TH|SUMMARY|LI|DT|DD)$/.test(element.tagName);
          function visit(node) {
            if(node.nodeType===Node.TEXT_NODE) {
              if(textUnit && node.parentElement.closest('p,h1,h2,h3,h4,h5,h6,pre,td,th,summary,li,dt,dd')!==element)return '';
              const range=document.createRange();range.selectNodeContents(node);
              return Array.from(range.getClientRects()).some(r=>r.height>0) ? node.data : '';
            }
            if(node.nodeType!==Node.ELEMENT_NODE)return '';
            if(node.hasAttribute('data-fixture-object-id') || !laidOut(node))return '';
            if(node.tagName==='BR')return '\n';
            let value=Array.from(node.childNodes).map(visit).join('');
            return node!==element && block(node) ? '\n'+value+'\n' : value;
          }
          return visit(element);
        }
        function inlineBaseline(element,bounds) {
          if(element.parentElement.closest('sup,sub'))return null;
          const parent=element.parentElement,html=parent.innerHTML,before=rect(element);
          const marker=document.createElement('span');marker.setAttribute('aria-hidden','true');
          marker.style.cssText='display:inline-block!important;width:0!important;height:0!important;padding:0!important;margin:0!important;border:0!important;line-height:0!important;vertical-align:baseline!important';
          parent.insertBefore(marker,element.nextSibling);
          const baseline=marker.getBoundingClientRect().top,after=rect(element);
          marker.remove();
          if(parent.innerHTML!==html)throw new Error('Object baseline probe changed reference DOM');
          return before.every((v,i)=>Math.abs(v-after[i])<0.1) && baseline>=bounds[1]-0.1 && baseline<=bounds[3]+0.1 ? baseline : null;
        }
        function imagePaint(element,bounds) {
          if(!(element.naturalWidth>0 && element.naturalHeight>0))return null;
          const style=getComputedStyle(element);
          if(style.boxShadow!=='none' || style.filter!=='none' || style.outlineStyle!=='none' && parseFloat(style.outlineWidth)>0 ||
            ['borderLeftWidth','borderRightWidth','borderTopWidth','borderBottomWidth'].some(name=>parseFloat(style[name])>0))return null;
          const left=bounds[0]+parseFloat(style.borderLeftWidth)+parseFloat(style.paddingLeft);
          const top=bounds[1]+parseFloat(style.borderTopWidth)+parseFloat(style.paddingTop);
          const width=bounds[2]-bounds[0]-parseFloat(style.borderLeftWidth)-parseFloat(style.borderRightWidth)-parseFloat(style.paddingLeft)-parseFloat(style.paddingRight);
          const height=bounds[3]-bounds[1]-parseFloat(style.borderTopWidth)-parseFloat(style.borderBottomWidth)-parseFloat(style.paddingTop)-parseFloat(style.paddingBottom);
          if(style.objectFit==='fill' || style.objectFit==='cover')return [left,top,left+width,top+height];
          if(style.objectPosition!=='50% 50%')return null;
          const factor=style.objectFit==='none' ? 1 : Math.min(width/element.naturalWidth,height/element.naturalHeight,style.objectFit==='scale-down' ? 1 : Infinity);
          const w=element.naturalWidth*factor,h=element.naturalHeight*factor;
          return [left+(width-w)/2,top+(height-h)/2,left+(width+w)/2,top+(height+h)/2];
        }
        for(const id of catalogue) {
          const element=elements.get(id),original=input[id];
          if(!element || !original) {excluded.push({id:id,reason:'not-in-rendered-reference-dom'});continue;}
          if(expectedHidden(element)) {excluded.push({id:id,reason:'expected-hidden'});continue;}
          if(closedDetails(element)) {excluded.push({id:id,reason:'closed-details'});continue;}
          if(!laidOut(element)) {excluded.push({id:id,reason:'not-rendered'});continue;}
          const kind=original.kind,semantics=Object.assign({},original.semantics),owner=ownerOf(element);
          let bounds=rect(element),paint=null,evidence='NOT_MEASURED';
          if(kind==='image') {semantics.alt=element.getAttribute('alt');semantics.url=element.currentSrc || element.src || null;paint=imagePaint(element,bounds);evidence=paint?'decoded-image-content-box':evidence;}
          else if(kind==='math') {
            const math=element.querySelector('math') || (element.tagName.toLowerCase()==='math' ? element : null);
            if(math) {
              const boxes=[math,...math.querySelectorAll('*')].map(rect);paint=union(boxes);bounds=rect(math);evidence=paint?'actual-mathml-engine-rect-union':evidence;
            }
          } else if(kind==='svg' || kind==='mermaid') {
            const svg=kind==='svg' ? element : element.querySelector('svg');
            if(svg) {bounds=rect(svg);if(['hidden','clip'].includes(getComputedStyle(svg).overflow)) {paint=bounds.slice();evidence='clipped-svg-engine-viewport';}}
          } else if(kind==='task') {
            semantics.checked=!!element.checked;semantics.disabled=!!element.disabled;
            const style=getComputedStyle(element);
            if(style.boxShadow==='none' && (style.outlineStyle==='none' || parseFloat(style.outlineWidth)===0)) {paint=bounds.slice();evidence='native-control-border-box';}
          }
          const visible=paint || bounds;
          if(!(visible[2]>0 && visible[0]<innerWidth && visible[3]>0 && visible[1]<innerHeight)) {excluded.push({id:id,reason:'outside-viewport'});continue;}
          if(!(bounds[2]>bounds[0] && bounds[3]>bounds[1])) {excluded.push({id:id,reason:'no-layout-box'});continue;}
          const inline=!(kind==='math' && semantics.display) && !block(element);
          const baseline=inline ? inlineBaseline(element,bounds) : null;
          measured.push({id:id,kind:kind,parentNodeId:owner===root?null:idOf(owner),inline:inline,
            semantics:semantics,boundsCssPx:bounds,paintBoundsCssPx:paint,paintEvidence:evidence,
            lineIndex:null,lineBaselineCssPx:baseline,segments:[],flowKey:flowKey(element),
            imageAudit:kind==='image'?{complete:element.complete,naturalWidth:element.naturalWidth,naturalHeight:element.naturalHeight,currentSrc:element.currentSrc}:null});
        }
        function withObjects(element,layout) {
          if(!layout || !layout.lines)return layout;
          const id=element===root?null:idOf(element);
          const own=measured.filter(object=>object.inline && object.parentNodeId===id);
          const rows=layout.lines.map(line=>Object.assign({},line,{objectIds:[]}));
          for(const object of own) {
            let row=rows.find(line=>line.flowKey===object.flowKey && object.lineBaselineCssPx!==null && line.baseline!==null && Math.abs(line.baseline-object.lineBaselineCssPx)<0.5);
            if(!row && object.lineBaselineCssPx!==null) {
              const candidates=rows.filter(line=>line.flowKey===object.flowKey && line.baseline===null && object.lineBaselineCssPx>=line.top && object.lineBaselineCssPx<=line.bottom);
              if(candidates.length===1)row=candidates[0];
            }
            if(!row) {row={top:object.boundsCssPx[1],bottom:object.boundsCssPx[3],left:object.boundsCssPx[0],right:object.boundsCssPx[2],text:'',baseline:object.lineBaselineCssPx,flowKey:object.flowKey,objectIds:[]};rows.push(row);}
            row.objectIds.push(object.id);
          }
          rows.sort((a,b)=>(a.baseline===null?a.top:a.baseline)-(b.baseline===null?b.top:b.baseline));
          for(const object of own) {
            const local=rows.filter(line=>line.flowKey===object.flowKey);
            object.lineIndex=local.findIndex(line=>line.objectIds.includes(object.id));
          }
          return {status:layout.status,lines:rows};
        }
        function finish() {
          const owners=new Set(measured.filter(object=>object.inline).map(object=>object.parentNodeId));
          for(const id of owners) {
            const owner=id===null ? root : Array.from(root.querySelectorAll('*')).find(element=>idOf(element)===id);
            if(owner)withObjects(owner,measureTextLines(owner,true));
          }
        }
        return {objects:measured,coverage:{excluded:excluded},visibleText:ordinaryText,withObjects:withObjects,flowKeyFor:flowKey,finish:finish};
      })();
    """
            .trimIndent()
}
