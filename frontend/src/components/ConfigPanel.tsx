import React, { useState, useEffect } from 'react';
import { Save, X, RotateCcw, Plus, Layout, Sliders, Filter, Target, Globe, FileJson, Copy, Activity, CheckSquare, Square, Info, Calculator } from 'lucide-react';

interface ConfigPanelProps {
    config: any;
    onSave: (config: any) => void;
    onClose: () => void;
    profiles: any[];
    onSavePreset: (name: string, description: string, config: any) => void;
}

const RangeInput = ({ label, values, onChange, step = 1, min = 0 }: { label: string, values: number[], onChange: (newValues: number[]) => void, step?: number, min?: number }) => {
    const [bulkMode, setBulkMode] = useState<'none' | 'comma' | 'generator'>('none');
    const [commaText, setCommaText] = useState('');
    const [genMin, setGenMin] = useState(min);
    const [genMax, setGenMax] = useState(min + step * 5);
    const [genStep, setGenStep] = useState(step);

    const handleApplyComma = () => {
        const parsed = commaText.split(',').map(s => parseFloat(s.trim())).filter(n => !isNaN(n));
        onChange(parsed);
        setBulkMode('none');
    };

    const handleApplyGenerator = () => {
        const generated = [];
        for (let i = genMin; i <= genMax; i += genStep) {
            generated.push(parseFloat(i.toFixed(4))); // fix floating point issues
        }
        onChange(generated);
        setBulkMode('none');
    };

    return (
        <div className="bg-slate-900/40 border border-slate-800 rounded-xl p-4 transition-all hover:border-slate-700">
            <div className="flex justify-between items-center mb-3">
                <div className="flex items-center gap-2">
                    <label className="text-slate-400 text-xs font-bold uppercase tracking-widest">{label}</label>
                    <Info size={12} className="text-slate-600 hover:text-blue-400 cursor-help" />
                </div>
                <div className="flex gap-1">
                    <button 
                        onClick={() => { setBulkMode('comma'); setCommaText(values.join(', ')); }}
                        className={`text-xs px-2 py-1 rounded-lg transition-colors ${bulkMode === 'comma' ? 'bg-blue-600 text-white' : 'bg-slate-800 text-slate-400 hover:bg-slate-700'}`}
                    >
                        CSV
                    </button>
                    <button 
                        onClick={() => setBulkMode('generator')}
                        className={`text-xs px-2 py-1 rounded-lg transition-colors ${bulkMode === 'generator' ? 'bg-blue-600 text-white' : 'bg-slate-800 text-slate-400 hover:bg-slate-700'}`}
                    >
                        Gen
                    </button>
                    <button 
                        onClick={() => onChange([...values, min])}
                        className="text-blue-400 hover:text-blue-300 p-1 rounded-lg bg-blue-400/5 transition-colors ml-1"
                        title="Add single value"
                    >
                        <Plus size={14} />
                    </button>
                </div>
            </div>

            {bulkMode === 'comma' && (
                <div className="mb-3 flex gap-2 items-center bg-[#0f172a] p-2 rounded-lg border border-slate-700">
                    <input 
                        type="text" 
                        value={commaText} 
                        onChange={e => setCommaText(e.target.value)} 
                        onKeyDown={e => e.key === 'Enter' && handleApplyComma()}
                        placeholder="e.g. 10, 20, 30" 
                        className="bg-transparent text-sm text-blue-100 flex-1 outline-none font-mono"
                    />
                    <button onClick={handleApplyComma} className="text-xs bg-emerald-600/20 text-emerald-400 px-2 py-1 rounded hover:bg-emerald-600/30">Apply</button>
                    <button onClick={() => setBulkMode('none')} className="text-xs text-slate-500 hover:text-slate-300 px-2 py-1"><X size={14}/></button>
                </div>
            )}

            {bulkMode === 'generator' && (
                <div className="mb-3 flex gap-2 items-center bg-[#0f172a] p-2 rounded-lg border border-slate-700 text-xs">
                    <input type="number" value={genMin} onChange={e => setGenMin(parseFloat(e.target.value))} placeholder="Min" className="bg-slate-800 w-14 px-1 py-1 rounded text-blue-100" />
                    <span className="text-slate-500">to</span>
                    <input type="number" value={genMax} onChange={e => setGenMax(parseFloat(e.target.value))} placeholder="Max" className="bg-slate-800 w-14 px-1 py-1 rounded text-blue-100" />
                    <span className="text-slate-500">step</span>
                    <input type="number" value={genStep} onChange={e => setGenStep(parseFloat(e.target.value))} placeholder="Step" className="bg-slate-800 w-14 px-1 py-1 rounded text-blue-100" />
                    <button onClick={handleApplyGenerator} className="bg-emerald-600/20 text-emerald-400 px-2 py-1 rounded hover:bg-emerald-600/30 ml-auto">Apply</button>
                    <button onClick={() => setBulkMode('none')} className="text-slate-500 hover:text-slate-300 px-1 py-1"><X size={14}/></button>
                </div>
            )}

            <div className="flex flex-wrap gap-2">
                {values.map((v: number, i: number) => (
                    <div key={i} className="flex items-center gap-1 bg-[#0f172a] border border-slate-700 rounded-lg pr-1 overflow-hidden group">
                        <input 
                            type="number"
                            step={step}
                            value={v}
                            onChange={(e) => {
                                const next = [...values];
                                next[i] = parseFloat(e.target.value);
                                onChange(next);
                            }}
                            className="bg-transparent text-sm text-blue-100 w-20 px-2 py-1 outline-none font-mono"
                        />
                        <button 
                            onClick={() => {
                                const next = [...values];
                                next.splice(i, 1);
                                onChange(next);
                            }}
                            className="text-slate-600 hover:text-red-400 transition-colors"
                        >
                            <X size={12} />
                        </button>
                    </div>
                ))}
                {values.length === 0 && <span className="text-red-400 text-xs italic">Empty range (Will cause optimizer to skip)</span>}
            </div>
        </div>
    );
};

const GICS_INDUSTRIES = [
    { code: 101010, name: 'Energy Equipment & Services', sector: 'Energy' },
    { code: 101020, name: 'Oil, Gas & Consumable Fuels', sector: 'Energy' },
    { code: 151010, name: 'Chemicals', sector: 'Materials' },
    { code: 151020, name: 'Construction Materials', sector: 'Materials' },
    { code: 151030, name: 'Containers & Packaging', sector: 'Materials' },
    { code: 151040, name: 'Metals & Mining', sector: 'Materials' },
    { code: 151050, name: 'Paper & Forest Products', sector: 'Materials' },
    { code: 201010, name: 'Aerospace & Defense', sector: 'Industrials' },
    { code: 201020, name: 'Building Products', sector: 'Industrials' },
    { code: 201030, name: 'Construction & Engineering', sector: 'Industrials' },
    { code: 201040, name: 'Electrical Equipment', sector: 'Industrials' },
    { code: 201050, name: 'Industrial Conglomerates', sector: 'Industrials' },
    { code: 201060, name: 'Machinery', sector: 'Industrials' },
    { code: 201070, name: 'Trading Companies & Distributors', sector: 'Industrials' },
    { code: 202010, name: 'Commercial Services & Supplies', sector: 'Industrials' },
    { code: 202020, name: 'Professional Services', sector: 'Industrials' },
    { code: 203010, name: 'Air Freight & Logistics', sector: 'Industrials' },
    { code: 203020, name: 'Airlines', sector: 'Industrials' },
    { code: 203030, name: 'Marine', sector: 'Industrials' },
    { code: 203040, name: 'Road & Rail', sector: 'Industrials' },
    { code: 203050, name: 'Transportation Infrastructure', sector: 'Industrials' },
    { code: 251010, name: 'Auto Components', sector: 'Consumer Discretionary' },
    { code: 251020, name: 'Automobiles', sector: 'Consumer Discretionary' },
    { code: 252010, name: 'Household Durables', sector: 'Consumer Discretionary' },
    { code: 252020, name: 'Leisure Products', sector: 'Consumer Discretionary' },
    { code: 252030, name: 'Textiles, Apparel & Luxury Goods', sector: 'Consumer Discretionary' },
    { code: 253010, name: 'Hotels, Restaurants & Leisure', sector: 'Consumer Discretionary' },
    { code: 253020, name: 'Diversified Consumer Services', sector: 'Consumer Discretionary' },
    { code: 255010, name: 'Distributors', sector: 'Consumer Discretionary' },
    { code: 255020, name: 'Internet & Direct Marketing Retail', sector: 'Consumer Discretionary' },
    { code: 255030, name: 'Multiline Retail', sector: 'Consumer Discretionary' },
    { code: 255040, name: 'Specialty Retail', sector: 'Consumer Discretionary' },
    { code: 301010, name: 'Food & Staples Retailing', sector: 'Consumer Staples' },
    { code: 302010, name: 'Beverages', sector: 'Consumer Staples' },
    { code: 302020, name: 'Food Products', sector: 'Consumer Staples' },
    { code: 302030, name: 'Tobacco', sector: 'Consumer Staples' },
    { code: 303010, name: 'Household Products', sector: 'Consumer Staples' },
    { code: 303020, name: 'Personal Products', sector: 'Consumer Staples' },
    { code: 351010, name: 'Health Care Equipment & Supplies', sector: 'Health Care' },
    { code: 351020, name: 'Health Care Providers & Services', sector: 'Health Care' },
    { code: 351030, name: 'Health Care Technology', sector: 'Health Care' },
    { code: 352010, name: 'Biotechnology', sector: 'Health Care' },
    { code: 352020, name: 'Pharmaceuticals', sector: 'Health Care' },
    { code: 352030, name: 'Life Sciences Tools & Services', sector: 'Health Care' },
    { code: 401010, name: 'Banks', sector: 'Financials' },
    { code: 401020, name: 'Thrifts & Mortgage Finance', sector: 'Financials' },
    { code: 402010, name: 'Diversified Financial Services', sector: 'Financials' },
    { code: 402020, name: 'Consumer Finance', sector: 'Financials' },
    { code: 402030, name: 'Capital Markets', sector: 'Financials' },
    { code: 402040, name: 'Mortgage Real Estate Investment Trusts (REITs)', sector: 'Financials' },
    { code: 403010, name: 'Insurance', sector: 'Financials' },
    { code: 451010, name: 'Internet Software & Services', sector: 'Information Technology' },
    { code: 451020, name: 'IT Services', sector: 'Information Technology' },
    { code: 451030, name: 'Software', sector: 'Information Technology' },
    { code: 452010, name: 'Communications Equipment', sector: 'Information Technology' },
    { code: 452020, name: 'Technology Hardware, Storage & Peripherals', sector: 'Information Technology' },
    { code: 452030, name: 'Electronic Equipment, Instruments & Components', sector: 'Information Technology' },
    { code: 453010, name: 'Semiconductors & Semiconductor Equipment', sector: 'Information Technology' },
    { code: 501010, name: 'Diversified Telecommunication Services', sector: 'Communication Services' },
    { code: 501020, name: 'Wireless Telecommunication Services', sector: 'Communication Services' },
    { code: 502010, name: 'Media', sector: 'Communication Services' },
    { code: 502020, name: 'Entertainment', sector: 'Communication Services' },
    { code: 502030, name: 'Interactive Media & Services', sector: 'Communication Services' },
    { code: 551010, name: 'Electric Utilities', sector: 'Utilities' },
    { code: 551020, name: 'Gas Utilities', sector: 'Utilities' },
    { code: 551030, name: 'Multi-Utilities', sector: 'Utilities' },
    { code: 551040, name: 'Water Utilities', sector: 'Utilities' },
    { code: 551050, name: 'Independent Power and Renewable Electricity Producers', sector: 'Utilities' },
    { code: 601010, name: 'Equity Real Estate Investment Trusts (REITs)', sector: 'Real Estate' },
    { code: 601020, name: 'Real Estate Management & Development', sector: 'Real Estate' }
];

const EXCHANGES = ["TASE", "NYSE", "NasdaqGS", "NasdaqGM", "NasdaqCM", "AMEX"];

const ConfigPanel: React.FC<ConfigPanelProps> = ({ config, onSave, onClose, profiles, onSavePreset }) => {
    const [localConfig, setCurrentLocalConfig] = useState<any>(JSON.parse(JSON.stringify(config)));
    const [activeTab, setActiveTab] = useState<'visual' | 'json'>('visual');
    const [activeCategory, setActiveTabCategory] = useState<'timing' | 'filters' | 'technicals' | 'sectors'>('timing');
    const [jsonText, setJsonText] = useState(JSON.stringify(config, null, 4));
    const [showSavePresetPrompt, setShowSavePresetPrompt] = useState(false);
    const [newPresetName, setNewPresetName] = useState('');
    const [newPresetDesc, setNewPresetDesc] = useState('');
    const [copySuccess, setCopySuccess] = useState(false);

    useEffect(() => {
        setJsonText(JSON.stringify(localConfig, null, 4));
    }, [localConfig]);

    const handleJsonChange = (val: string) => {
        setJsonText(val);
        try {
            const parsed = JSON.parse(val);
            setCurrentLocalConfig(parsed);
        } catch (e) {
            // Wait for valid JSON
        }
    };

    const updateField = (key: string, value: any) => {
        setCurrentLocalConfig((prev: any) => ({ ...prev, [key]: value }));
    };

    const handleSave = () => {
        onSave(localConfig);
        onClose();
    };

    const handleSaveAsPreset = () => {
        if (!newPresetName) return;
        onSavePreset(newPresetName, newPresetDesc, localConfig);
        setShowSavePresetPrompt(false);
    };

    const copyToClipboard = () => {
        navigator.clipboard.writeText(jsonText);
        setCopySuccess(true);
        setTimeout(() => setCopySuccess(false), 2000);
    };

    const toggleSector = (code: number) => {
        const sectors = localConfig.sectors || [];
        if (sectors.includes(code)) {
            updateField('sectors', sectors.filter((s: number) => s !== code));
        } else {
            updateField('sectors', [...sectors, code]);
        }
    };

    const toggleAllSectors = (include: boolean) => {
        if (include) {
            updateField('sectors', GICS_INDUSTRIES.map(i => i.code));
        } else {
            updateField('sectors', []);
        }
    };

    const toggleSectorGroup = (sectorName: string) => {
        const currentSectors = localConfig.sectors || [];
        const groupCodes = GICS_INDUSTRIES.filter(i => i.sector === sectorName).map(i => i.code);
        const allInGroupSelected = groupCodes.every(code => currentSectors.includes(code));

        if (allInGroupSelected) {
            updateField('sectors', currentSectors.filter((s: number) => !groupCodes.includes(s)));
        } else {
            const uniqueNewSectors = Array.from(new Set([...currentSectors, ...groupCodes]));
            updateField('sectors', uniqueNewSectors);
        }
    };

    const toggleExchange = (ex: string) => {
        const exchanges = localConfig.exchanges || [];
        if (exchanges.includes(ex)) {
            updateField('exchanges', exchanges.filter((e: string) => e !== ex));
        } else {
            updateField('exchanges', [...exchanges, ex]);
        }
    };

    const calculateSimulations = () => {
        let count = 1;
        const fields = [
            'startTimes', 'selectTimes', 'searchTimes', 'longMovingAvgTimes',
            'sellCutOffPerc', 'lowerPriceToLongAvgBuyIn', 'higherPriceToLongAvgBuyIn',
            'timeFrameForUpwardLongAvg', 'timeFrameForOscillator', 'maxPERatios',
            'aboveAvgRatingPricePerc', 'timeFrameForUpwardShortPrice', 'maxRSI',
            'minMarketCap', 'maxMarketCap', 'minRatesOfAvgInc', 'minRatings', 'maxRatings',
            'riskFreeRate'
        ];
        fields.forEach(f => {
            if (Array.isArray(localConfig[f]) && localConfig[f].length > 0) {
                count *= localConfig[f].length;
            }
        });
        return count;
    };

    const uniqueSectors = Array.from(new Set(GICS_INDUSTRIES.map(i => i.sector)));

    return (
        <div className="fixed inset-0 bg-[#020617]/90 backdrop-blur-md z-[110] flex items-center justify-center p-4 lg:p-12 overflow-hidden">
            <div className="bg-[#1e293b] w-full max-w-7xl h-full rounded-3xl border border-slate-700 shadow-2xl overflow-hidden flex flex-col animate-in zoom-in-95 duration-200">
                {/* Header */}
                <div className="px-8 py-6 border-b border-slate-700 flex justify-between items-center bg-slate-900/50">
                    <div className="flex items-center gap-4">
                        <div className="p-3 bg-blue-600/20 text-blue-400 rounded-2xl">
                            <Sliders size={24} />
                        </div>
                        <div>
                            <h3 className="text-2xl font-bold text-white tracking-tight">Strategy Forge</h3>
                            <p className="text-slate-400 text-sm italic">Fine-tune hydration parameters and optimization sweeps</p>
                        </div>
                    </div>
                    
                    <div className="flex items-center gap-3">
                        <div className="bg-slate-800 p-1 rounded-xl flex mr-4">
                            <button 
                                onClick={() => setActiveTab('visual')}
                                className={`px-4 py-1.5 rounded-lg text-sm font-bold flex items-center gap-2 transition-all ${activeTab === 'visual' ? 'bg-[#0f172a] text-blue-400 shadow-lg' : 'text-slate-500 hover:text-slate-300'}`}
                            >
                                <Layout size={16} /> Dashboard
                            </button>
                            <button 
                                onClick={() => setActiveTab('json')}
                                className={`px-4 py-1.5 rounded-lg text-sm font-bold flex items-center gap-2 transition-all ${activeTab === 'json' ? 'bg-[#0f172a] text-blue-400 shadow-lg' : 'text-slate-500 hover:text-slate-300'}`}
                            >
                                <FileJson size={16} /> JSON
                            </button>
                        </div>
                        <button onClick={onClose} className="p-2 hover:bg-slate-700 rounded-full text-slate-400 transition-colors">
                            <X size={28} />
                        </button>
                    </div>
                </div>
                
                <div className="flex-1 flex min-h-0 overflow-hidden">
                    {/* Sidebar Tabs */}
                    {activeTab === 'visual' && (
                        <aside className="w-64 border-r border-slate-800 bg-slate-900/20 p-4 flex flex-col gap-2">
                            <button 
                                onClick={() => setActiveTabCategory('timing')}
                                className={`w-full flex items-center gap-3 px-4 py-3 rounded-xl text-left transition-all ${activeCategory === 'timing' ? 'bg-blue-600/10 text-blue-400 border border-blue-600/20' : 'text-slate-500 hover:bg-slate-800'}`}
                            >
                                <Target size={18} />
                                <span className="font-bold">Timing</span>
                            </button>
                            <button 
                                onClick={() => setActiveTabCategory('filters')}
                                className={`w-full flex items-center gap-3 px-4 py-3 rounded-xl text-left transition-all ${activeCategory === 'filters' ? 'bg-blue-600/10 text-blue-400 border border-blue-600/20' : 'text-slate-500 hover:bg-slate-800'}`}
                            >
                                <Filter size={18} />
                                <span className="font-bold">Filters</span>
                            </button>
                            <button 
                                onClick={() => setActiveTabCategory('technicals')}
                                className={`w-full flex items-center gap-3 px-4 py-3 rounded-xl text-left transition-all ${activeCategory === 'technicals' ? 'bg-blue-600/10 text-blue-400 border border-blue-600/20' : 'text-slate-500 hover:bg-slate-800'}`}
                            >
                                <Activity size={18} />
                                <span className="font-bold">Technicals</span>
                            </button>
                            <button 
                                onClick={() => setActiveTabCategory('sectors')}
                                className={`w-full flex items-center gap-3 px-4 py-3 rounded-xl text-left transition-all ${activeCategory === 'sectors' ? 'bg-blue-600/10 text-blue-400 border border-blue-600/20' : 'text-slate-500 hover:bg-slate-800'}`}
                            >
                                <Globe size={18} />
                                <span className="font-bold">Markets</span>
                            </button>

                            <div className="mt-auto pt-4 border-t border-slate-800">
                                <h4 className="text-slate-600 text-[10px] font-black uppercase tracking-widest px-4 mb-3">Load Preset</h4>
                                <div className="space-y-1 overflow-y-auto max-h-48 scrollbar-thin scrollbar-thumb-slate-800 pr-2">
                                    {profiles.map(p => (
                                        <button 
                                            key={p.id}
                                            onClick={() => setCurrentLocalConfig(JSON.parse(p.configJson))}
                                            className="w-full text-left px-4 py-2 rounded-lg text-xs text-slate-400 hover:bg-slate-800 hover:text-white transition-colors flex items-center gap-2 group"
                                        >
                                            <Copy size={12} className="opacity-0 group-hover:opacity-100 transition-opacity" />
                                            {p.name}
                                        </button>
                                    ))}
                                </div>
                            </div>
                        </aside>
                    )}

                    {/* Content Area */}
                    <div className="flex-1 overflow-y-auto p-8 scrollbar-thin scrollbar-thumb-slate-800">
                        {activeTab === 'visual' ? (
                            <div className="max-w-5xl space-y-8 pb-12">
                                {activeCategory === 'timing' && (
                                    <div className="grid grid-cols-1 md:grid-cols-2 gap-6 animate-in fade-in slide-in-from-bottom-4">
                                        <RangeInput label="Start Times (Days Ago)" values={localConfig.startTimes || []} onChange={(v) => updateField('startTimes', v)} />
                                        <RangeInput label="Search Window (Days)" values={localConfig.searchTimes || []} onChange={(v) => updateField('searchTimes', v)} />
                                        <RangeInput label="Selection Period (Days)" values={localConfig.selectTimes || []} onChange={(v) => updateField('selectTimes', v)} />
                                        <RangeInput label="Moving Average Period" values={localConfig.longMovingAvgTimes || []} onChange={(v) => updateField('longMovingAvgTimes', v)} />
                                    </div>
                                )}

                                {activeCategory === 'filters' && (
                                    <div className="grid grid-cols-1 md:grid-cols-2 gap-6 animate-in fade-in slide-in-from-bottom-4">
                                        <RangeInput label="Sell Cut-Off %" values={localConfig.sellCutOffPerc || []} onChange={(v) => updateField('sellCutOffPerc', v)} step={0.01} />
                                        <RangeInput label="Lower MA Gap Buy-In" values={localConfig.lowerPriceToLongAvgBuyIn || []} onChange={(v) => updateField('lowerPriceToLongAvgBuyIn', v)} step={0.01} />
                                        <RangeInput label="Higher MA Gap Buy-In" values={localConfig.higherPriceToLongAvgBuyIn || []} onChange={(v) => updateField('higherPriceToLongAvgBuyIn', v)} step={0.01} />
                                        <RangeInput label="Min Market Cap (M)" values={localConfig.minMarketCap || []} onChange={(v) => updateField('minMarketCap', v)} step={100} />
                                        <RangeInput label="Max Market Cap (M)" values={localConfig.maxMarketCap || []} onChange={(v) => updateField('maxMarketCap', v)} step={100} />
                                        <RangeInput label="Risk Free Rate (Annual)" values={localConfig.riskFreeRate || []} onChange={(v) => updateField('riskFreeRate', v)} step={0.01} />
                                    </div>
                                )}

                                {activeCategory === 'technicals' && (
                                    <div className="grid grid-cols-1 md:grid-cols-2 gap-6 animate-in fade-in slide-in-from-bottom-4">
                                        <RangeInput label="MA Upward Timeframe" values={localConfig.timeFrameForUpwardLongAvg || []} onChange={(v) => updateField('timeFrameForUpwardLongAvg', v)} />
                                        <RangeInput label="Oscillator Window" values={localConfig.timeFrameForOscillator || []} onChange={(v) => updateField('timeFrameForOscillator', v)} />
                                        <RangeInput label="Max PE Ratio" values={localConfig.maxPERatios || []} onChange={(v) => updateField('maxPERatios', v)} />
                                        <RangeInput label="Min Rating (1-5)" values={localConfig.minRatings || []} onChange={(v) => updateField('minRatings', v)} step={0.1} />
                                        <RangeInput label="Max Rating (1-5)" values={localConfig.maxRatings || []} onChange={(v) => updateField('maxRatings', v)} step={0.1} />
                                        <RangeInput label="Max RSI" values={localConfig.maxRSI || []} onChange={(v) => updateField('maxRSI', v)} step={1} />
                                    </div>
                                )}

                                {activeCategory === 'sectors' && (
                                    <div className="space-y-8 animate-in fade-in slide-in-from-bottom-4">
                                        <div className="bg-slate-900/40 border border-slate-800 rounded-2xl p-6">
                                            <div className="flex justify-between items-center mb-6">
                                                <div>
                                                    <h4 className="text-white font-bold text-lg">Target Sectors</h4>
                                                    <p className="text-slate-500 text-sm">Select industries to hydrate during analysis</p>
                                                </div>
                                                <div className="flex gap-2">
                                                    <button 
                                                        onClick={() => toggleAllSectors(true)}
                                                        className="px-3 py-1.5 rounded-lg bg-blue-600/10 text-blue-400 text-xs font-bold border border-blue-600/20 hover:bg-blue-600/20 transition-all"
                                                    >
                                                        Include All
                                                    </button>
                                                    <button 
                                                        onClick={() => toggleAllSectors(false)}
                                                        className="px-3 py-1.5 rounded-lg bg-slate-800 text-slate-400 text-xs font-bold border border-slate-700 hover:bg-slate-700 transition-all"
                                                    >
                                                        Exclude All
                                                    </button>
                                                </div>
                                            </div>

                                            {/* Sector Level Toggles */}
                                            <div className="flex flex-wrap gap-2 mb-6 p-4 bg-[#0f172a] rounded-xl border border-slate-800">
                                                <span className="text-slate-500 text-[10px] font-black uppercase tracking-widest w-full mb-1">Bulk Sector Actions</span>
                                                {uniqueSectors.map(sector => (
                                                    <button 
                                                        key={sector}
                                                        onClick={() => toggleSectorGroup(sector)}
                                                        className="px-3 py-1 rounded-lg bg-slate-800/50 border border-slate-700 text-slate-400 text-[10px] font-bold hover:bg-slate-700 hover:text-white transition-all"
                                                    >
                                                        {sector}
                                                    </button>
                                                ))}
                                            </div>
                                            
                                            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3 max-h-[400px] overflow-y-auto pr-4 scrollbar-thin scrollbar-thumb-slate-800">
                                                {GICS_INDUSTRIES.map(industry => {
                                                    const isSelected = (localConfig.sectors || []).includes(industry.code);
                                                    return (
                                                        <button 
                                                            key={industry.code}
                                                            onClick={() => toggleSector(industry.code)}
                                                            className={`flex items-center gap-3 px-4 py-3 rounded-xl text-left border transition-all ${
                                                                isSelected 
                                                                ? 'bg-blue-600/10 border-blue-600/40 text-blue-100 shadow-lg shadow-blue-900/10' 
                                                                : 'bg-[#0f172a] border-slate-800 text-slate-500 hover:border-slate-600'
                                                            }`}
                                                        >
                                                            {isSelected ? <CheckSquare size={18} className="text-blue-400" /> : <Square size={18} />}
                                                            <div className="flex flex-col min-w-0">
                                                                <span className="text-xs font-bold leading-tight truncate">{industry.name}</span>
                                                                <span className="text-[10px] text-slate-600 uppercase font-black tracking-tighter">{industry.sector}</span>
                                                            </div>
                                                        </button>
                                                    );
                                                })}
                                            </div>
                                        </div>

                                        <div className="bg-slate-900/40 border border-slate-800 rounded-2xl p-6">
                                            <h4 className="text-white font-bold mb-4">Exchanges</h4>
                                            <div className="flex flex-wrap gap-3">
                                                {EXCHANGES.map(ex => {
                                                    const isSelected = (localConfig.exchanges || []).includes(ex);
                                                    return (
                                                        <button 
                                                            key={ex}
                                                            onClick={() => toggleExchange(ex)}
                                                            className={`px-6 py-2 rounded-xl font-bold transition-all border ${
                                                                isSelected 
                                                                ? 'bg-emerald-600/10 border-emerald-600/40 text-emerald-400 shadow-lg' 
                                                                : 'bg-[#0f172a] border-slate-800 text-slate-500 hover:border-slate-600'
                                                            }`}
                                                        >
                                                            {ex}
                                                        </button>
                                                    );
                                                })}
                                            </div>
                                        </div>

                                        <div className="bg-slate-900/40 border border-slate-800 rounded-2xl p-6">
                                            <label className="text-slate-400 text-xs font-bold uppercase tracking-widest mb-3 block">Output Path</label>
                                            <input 
                                                type="text"
                                                value={localConfig.outputPath}
                                                onChange={(e) => updateField('outputPath', e.target.value)}
                                                className="w-full bg-[#0f172a] border border-slate-700 rounded-xl px-4 py-3 text-blue-100 font-mono focus:border-blue-500 outline-none transition-all shadow-inner"
                                            />
                                        </div>
                                    </div>
                                )}
                            </div>
                        ) : (
                            <div className="h-full flex flex-col gap-4 animate-in fade-in">
                                <div className="flex justify-between items-center bg-[#020617] px-6 py-3 rounded-t-2xl border-x border-t border-slate-800">
                                    <span className="text-xs font-mono text-slate-500 uppercase tracking-widest">Raw Configuration (Read/Write)</span>
                                    <button 
                                        onClick={copyToClipboard}
                                        className={`flex items-center gap-2 px-3 py-1 rounded-lg text-xs font-bold transition-all ${copySuccess ? 'bg-emerald-500 text-white' : 'bg-slate-800 text-slate-400 hover:bg-slate-700'}`}
                                    >
                                        {copySuccess ? 'Copied!' : <><Copy size={12} /> Copy JSON</>}
                                    </button>
                                </div>
                                <div className="flex-1 min-h-0 bg-[#020617] rounded-b-2xl border border-slate-800 shadow-inner p-6">
                                    <textarea 
                                        value={jsonText}
                                        onChange={(e) => handleJsonChange(e.target.value)}
                                        className="w-full h-full bg-transparent font-mono text-sm text-blue-300 focus:outline-none resize-none leading-relaxed"
                                        spellCheck={false}
                                    />
                                </div>
                            </div>
                        )}
                    </div>
                </div>

                {/* Footer */}
                <div className="px-8 py-6 border-t border-slate-700 flex justify-between items-center bg-slate-900/50">
                    <div className="flex gap-6 items-center">
                        <div className="flex flex-col">
                            <div className="flex items-center gap-2 text-blue-400">
                                <Calculator size={16} />
                                <span className="text-sm font-black uppercase tracking-wider">{calculateSimulations().toLocaleString()}</span>
                            </div>
                            <span className="text-slate-500 text-[10px] font-bold uppercase tracking-tight">Total Simulations Sweep</span>
                        </div>
                        <div className="h-8 w-px bg-slate-800"></div>
                        <div className="flex gap-3">
                            <button 
                                onClick={() => setShowSavePresetPrompt(true)}
                                className="px-6 py-3 rounded-2xl bg-emerald-600/10 text-emerald-400 font-bold hover:bg-emerald-600/20 transition-all flex items-center gap-2 border border-emerald-600/20"
                            >
                                <Save size={18} /> Save as New Preset
                            </button>
                            <button 
                                onClick={() => {
                                    setCurrentLocalConfig(JSON.parse(JSON.stringify(config)));
                                    setJsonText(JSON.stringify(config, null, 4));
                                }}
                                className="px-6 py-3 rounded-2xl bg-slate-800 text-slate-300 hover:bg-slate-700 flex items-center gap-2 transition-all font-bold"
                            >
                                <RotateCcw size={18} /> Revert
                            </button>
                        </div>
                    </div>
                    
                    <div className="flex gap-4">
                        <button 
                            onClick={onClose}
                            className="px-8 py-3 rounded-2xl text-slate-400 font-bold hover:text-white transition-colors"
                        >
                            Cancel
                        </button>
                        <button 
                            onClick={handleSave}
                            className="px-12 py-3 rounded-2xl bg-blue-600 text-white font-black text-lg hover:bg-blue-500 hover:scale-[1.02] active:scale-95 transition-all shadow-xl shadow-blue-600/20"
                        >
                            Apply Strategy
                        </button>
                    </div>
                </div>
            </div>

            {/* Save Preset Modal */}
            {showSavePresetPrompt && (
                <div className="fixed inset-0 bg-black/60 backdrop-blur-sm z-[120] flex items-center justify-center p-4">
                    <div className="bg-[#1e293b] w-full max-w-md rounded-3xl border border-slate-700 p-8 shadow-2xl animate-in zoom-in-95 duration-200">
                        <h4 className="text-2xl font-bold text-white mb-6">Create New Preset</h4>
                        <div className="space-y-4 mb-8">
                            <div>
                                <label className="block text-slate-400 text-xs font-bold uppercase tracking-widest mb-2">Preset Name</label>
                                <input 
                                    autoFocus
                                    type="text" 
                                    value={newPresetName}
                                    onChange={(e) => setNewPresetName(e.target.value)}
                                    placeholder="e.g. Aggressive Tech Scan"
                                    className="w-full bg-[#0f172a] border border-slate-800 rounded-xl px-4 py-3 text-white focus:border-blue-500 outline-none transition-all"
                                />
                            </div>
                            <div>
                                <label className="block text-slate-400 text-xs font-bold uppercase tracking-widest mb-2">Description</label>
                                <textarea 
                                    value={newPresetDesc}
                                    onChange={(e) => setNewPresetDesc(e.target.value)}
                                    placeholder="Describe the logic behind this strategy..."
                                    className="w-full bg-[#0f172a] border border-slate-800 rounded-xl px-4 py-3 text-white focus:border-blue-500 outline-none transition-all h-24 resize-none"
                                />
                            </div>
                        </div>
                        <div className="flex gap-3">
                            <button 
                                onClick={() => setShowSavePresetPrompt(false)}
                                className="flex-1 py-3 rounded-xl text-slate-400 font-bold hover:text-white transition-colors"
                            >
                                Back
                            </button>
                            <button 
                                onClick={handleSaveAsPreset}
                                disabled={!newPresetName}
                                className="flex-[2] py-3 rounded-xl bg-blue-600 text-white font-bold hover:bg-blue-500 disabled:opacity-50 disabled:cursor-not-allowed transition-all shadow-lg shadow-blue-600/20"
                            >
                                Save Preset
                            </button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
};

export default ConfigPanel;
